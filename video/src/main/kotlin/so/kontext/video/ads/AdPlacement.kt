package so.kontext.video.ads

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.Constants
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.tracking.AdTracker
import so.kontext.video.tracking.ViewabilityMeter

/**
 * Shared machinery of every placement: the pending queue, the optional
 * reveal delay, tracking, viewability, and teardown. Subclasses build the
 * view, lay it out, and add format-specific behaviour (video playback).
 *
 * Every public method must be called on the main thread.
 */
public abstract class AdPlacement internal constructor(
    public val format: VideoAdFormat,
    public val videoId: String,
    internal val emit: (VideoAdEvent) -> Unit,
) {
    private val queue = mutableListOf<VideoAd>()

    /** Ads not yet shown, in the order the server returned them. */
    public val pending: List<VideoAd>
        get() = queue.toList()

    /** `true` when an ad is loaded and none is on screen or about to be. */
    public val isReady: Boolean
        get() = !isDestroyed && view == null && revealJob == null && queue.isNotEmpty()

    /**
     * The ad `show` would present now, or `null` when [isReady] is `false`
     * (nothing loaded, an ad on screen or about to be, destroyed). Read its
     * `ecpm` to rank it against your other demand before you show it.
     */
    public open val nextAd: VideoAd?
        get() = if (isReady) queue.first() else null

    public var isDestroyed: Boolean = false
        private set

    internal var loadHandler: (() -> Unit)? = null

    /** The ad view (what viewability measures). */
    internal var view: View? = null
        private set

    /** What `mount` put into the container and what `hide` removes — the view itself or a layer around it. */
    private var root: View? = null
    internal var current: VideoAd? = null
        private set
    internal var tracker: AdTracker? = null
        private set
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var meter: ViewabilityMeter? = null
    private var revealJob: Job? = null

    /** Requests another ad for this placement only (one `/instream/preload` with just this format). */
    public fun load() {
        loadHandler?.invoke()
    }

    /**
     * Removes the ad if it is on screen (or cancels a pending reveal). Fires
     * `overlay_view_duration`. Call whenever playback resumes.
     */
    public open fun hide() {
        hide(userDismissed = false)
    }

    internal fun hide(userDismissed: Boolean) {
        revealJob?.cancel()
        revealJob = null
        if (view == null) return
        meter?.end()
        meter = null
        willHide(userDismissed)
        tracker?.fire(AdTracker.Event.OVERLAY_VIEW_DURATION)
        if (userDismissed) tracker?.fire(AdTracker.Event.CLOSE)
        unmount()
        clearCurrent()
    }

    // Subclass hooks

    /** Build the ad view and wire its callbacks to `onClick` / `onCreativeRendered`. */
    internal abstract fun makeView(context: Context, ad: VideoAd): View

    /** Add the view to the container; returns what to remove later (the view or a layer around it). */
    internal abstract fun mount(view: View, container: ViewGroup): View

    /** Called before the view is removed on `hide`; the tracker is still alive. Video placements fire `skip` here. */
    internal open fun willHide(userDismissed: Boolean) {}

    /** Called once a destroyed placement's view has left the hierarchy. */
    internal open fun afterTeardown() {}

    // Shared flow

    /**
     * Shows the next ad in `container` — immediately, unless the server set
     * `trigger.delay_ms` for this ad, in which case a `hide()` during the wait
     * cancels the reveal and the ad stays queued. The ad is consumed only when
     * it actually appears. Returns `false` when [isReady] is `false`.
     */
    internal fun present(container: ViewGroup): Boolean {
        if (!isReady) return false
        val ad = queue.first()
        val delayMs = ad.trigger.delayMs ?: 0
        if (delayMs <= 0) {
            queue.removeAt(0)
            reveal(ad, container)
            return true
        }
        revealJob = scope.launch {
            delay(delayMs)
            val next = queue.firstOrNull()
            if (next == null || next.adId != ad.adId) return@launch
            revealJob = null
            queue.removeAt(0)
            reveal(next, container)
        }
        return true
    }

    private fun reveal(ad: VideoAd, container: ViewGroup) {
        val tracker = AdTracker(ad.tracking)
        // `current` is set before `mount` so subclasses can read the ad's placement while laying out.
        this.current = ad
        this.tracker = tracker
        val view = makeView(container.context, ad)
        val root = mount(view, container)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(FADE_MS).start()
        this.view = view
        this.root = root
        emit(VideoAdEvent.Shown(VideoAdEvent.AdData(format, videoId, ad.adId)))
    }

    /**
     * The creative could not render (image failed to load, video URL bad).
     * Removes the ad without firing view/impression tracking and surfaces
     * `Error`, so the app is never left with a blank ad on screen.
     */
    internal open fun onCreativeFailed(message: String) {
        revealJob?.cancel()
        revealJob = null
        meter?.end()
        meter = null
        willHide(userDismissed = false)
        unmount()
        clearCurrent()
        emit(VideoAdEvent.Error(VideoAdEvent.ErrorData(videoId, message)))
    }

    /** The creative is on screen (image painted / first video frame): this is the impression. */
    internal fun onCreativeRendered() {
        val view = view ?: return
        val tracker = tracker ?: return
        tracker.fire(AdTracker.Event.IMPRESSION)
        tracker.fire(AdTracker.Event.CREATIVE_VIEW)
        val meter = ViewabilityMeter(view, scope) { verdict -> tracker.fire(verdict) }
        meter.start()
        this.meter = meter
    }

    /** Opens the click-through via the server (`/impression/{bid}/redirect` records the click and forwards) in Custom Tabs. */
    internal fun onClick() {
        val ad = current ?: return
        val context = view?.context ?: return
        emit(VideoAdEvent.Clicked(VideoAdEvent.AdData(format, videoId, ad.adId)))
        ClickThrough.open(context, ad.tracking.clickThrough)
    }

    internal fun onUserDismiss() {
        val ad = current ?: return
        hide(userDismissed = true)
        emit(VideoAdEvent.Closed(VideoAdEvent.AdData(format, videoId, ad.adId)))
    }

    internal fun append(ads: List<VideoAd>) {
        if (isDestroyed) return
        queue += ads
    }

    internal fun moveToFront(index: Int) {
        if (index !in queue.indices || index == 0) return
        val ad = queue.removeAt(index)
        queue.add(0, ad)
    }

    /**
     * Tears the placement down: fires the end-of-display pings for an ad on
     * screen, keeps its view alive for [Constants.TEARDOWN_DELAY_MS], then
     * removes it and drops pending ads. Called by `VideoAds.destroy()`.
     */
    internal open fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        queue.clear()
        revealJob?.cancel()
        revealJob = null
        val root = root
        if (root == null) {
            // No view on screen, but a subclass may still hold resources (an interstitial's player).
            willHide(userDismissed = false)
            afterTeardown()
            return
        }
        root.isEnabled = false
        willHide(userDismissed = false)
        meter?.end()
        meter = null
        tracker?.fire(AdTracker.Event.OVERLAY_VIEW_DURATION)
        scope.launch {
            delay(Constants.TEARDOWN_DELAY_MS)
            if (this@AdPlacement.root === root) {
                unmount()
                clearCurrent()
            }
            afterTeardown()
        }
    }

    private fun unmount() {
        val root = root ?: return
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun clearCurrent() {
        view = null
        root = null
        current = null
        tracker = null
    }

    private companion object {
        const val FADE_MS = 300L
    }
}
