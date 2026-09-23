package so.kontext.video.ads.overlay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.Constants
import so.kontext.video.ads.AdPlacement
import so.kontext.video.ads.BannerAdView
import so.kontext.video.ads.BannerPosition
import so.kontext.video.ads.BannerStyle
import so.kontext.video.ads.mountBanner
import so.kontext.video.internal.log
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat

/**
 * The `overlay` placement of one video: image banners shown **while the
 * episode plays**, several per video.
 *
 * Two modes, decided by the server per ad:
 *
 * **Windowed** (`trigger { kind: "range", from_ms, to_ms }`) — the server
 * knows the content and derived scene windows. The SDK never touches the
 * player: the app feeds it the playhead and is *told* when a window opens or
 * closes; showing is always the app's call:
 *
 * ```kotlin
 * ads.overlay.onWindowOpen = { ad -> ads.overlay.show(ad, playerContainer) }
 * ads.overlay.onWindowClose = { ads.overlay.hide() }
 * // when the episode starts playing: the SDK reads the position 4× a second
 * ads.overlay.trackPlayhead { player.currentPosition }
 * ```
 *
 * **Manual** (`trigger { kind: "manual" }`) — the publisher shared no content,
 * so there are no windows; the app decides the moment, exactly like the pause ad:
 *
 * ```kotlin
 * if (ads.overlay.isReady) ads.overlay.show(playerContainer)
 * ads.overlay.hide()
 * ```
 *
 * The modes mix: `show` prefers the ad whose window is open now, then manual
 * ads, then anything pending — so a manual `show` never steals an ad the
 * server scheduled for a later scene. Windows are hints; an ignored window
 * leaves the ad in `pending`.
 */
public class OverlayAd internal constructor(
    videoId: String,
    emit: (VideoAdEvent) -> Unit,
) : AdPlacement(VideoAdFormat.OVERLAY, videoId, emit) {
    /**
     * Current playback position of the episode, in milliseconds. The SDK
     * compares it with the pending ads' windows and calls [onWindowOpen] /
     * [onWindowClose]. Most apps let [trackPlayhead] set it; set it yourself
     * only when your player has a progress callback you'd rather use.
     */
    public var playheadMs: Long = 0
        set(value) {
            field = value
            evaluateWindows()
        }

    /** A pending ad's window contains the playhead. Show it (or don't). */
    public var onWindowOpen: ((VideoAd) -> Unit)? = null

    /** The window of the ad on screen (or the last opened one) ended. Typically `hide()`. */
    public var onWindowClose: ((VideoAd) -> Unit)? = null

    /** The ad whose window is open right now, if any. */
    public var openWindowAd: VideoAd? = null
        private set

    private var style = BannerStyle()
    private var position: BannerPosition? = null
    private var playheadJob: Job? = null

    /**
     * Keeps [playheadMs] up to date for you: the SDK calls [playhead] on the
     * main thread every 250 ms and feeds the result in, so windows open and
     * close without a ticker in your app. Call it when the episode starts
     * playing:
     *
     * ```kotlin
     * ads.overlay.trackPlayhead { player.currentPosition }
     * ```
     *
     * Tracking stops on [stopTrackingPlayhead] and on `VideoAds.destroy()`, so
     * a destroyed handle never keeps your player alive. Calling it again
     * replaces the previous [playhead]. If [playhead] throws (a released
     * player, say), tracking stops and the error is logged.
     */
    public fun trackPlayhead(playhead: () -> Long) {
        stopTrackingPlayhead()
        if (isDestroyed) return
        playheadJob = scope.launch {
            while (true) {
                val ms = try {
                    playhead()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Just end this job: a finished job is inert, and `Main.immediate`
                    // runs the first read inside `trackPlayhead` before `playheadJob`
                    // is even assigned, so clearing the field here could never be
                    // relied on.
                    log("overlay: trackPlayhead lambda threw, tracking stopped: $e")
                    return@launch
                }
                playheadMs = ms
                delay(Constants.PLAYHEAD_POLL_MS)
            }
        }
    }

    /**
     * Stops [trackPlayhead] — for example while the episode is off screen but
     * its handle stays alive for later. [playheadMs] keeps its last value.
     */
    public fun stopTrackingPlayhead() {
        playheadJob?.cancel()
        playheadJob = null
    }

    /**
     * Shows an overlay inside `container` and consumes it, preferring the ad
     * whose window is open now, then manual (window-less) ads, then the next
     * pending — so a manual show never steals an ad scheduled for a later
     * scene. Position defaults to the server's `placement.position`, then
     * [BannerPosition.BOTTOM]. Returns `false` when [isReady] is `false`.
     *
     * `container` is the view that fills the video area — any `ViewGroup`
     * whose children may overlap, such as a `FrameLayout`.
     */
    public fun show(
        container: ViewGroup,
        position: BannerPosition? = null,
        style: BannerStyle = BannerStyle(),
    ): Boolean {
        if (!isReady) return false
        val preferred = openWindowAd ?: pending.firstOrNull { it.trigger.fromMs == null }
        if (preferred != null) {
            val index = pending.indexOfFirst { it.adId == preferred.adId }
            if (index > 0) moveToFront(index)
        }
        this.position = position
        this.style = style
        return present(container)
    }

    /** Shows a specific pending ad (from [onWindowOpen] or [pending]). */
    public fun show(
        ad: VideoAd,
        container: ViewGroup,
        position: BannerPosition? = null,
        style: BannerStyle = BannerStyle(),
    ): Boolean {
        val index = pending.indexOfFirst { it.adId == ad.adId }
        if (index < 0) return false
        moveToFront(index)
        this.position = position
        this.style = style
        return present(container)
    }

    override fun destroy() {
        stopTrackingPlayhead()
        super.destroy()
    }

    override fun makeView(context: Context, ad: VideoAd): View = BannerAdView(
        context = context,
        ad = ad,
        style = style,
        scope = scope,
        onClick = ::onClick,
        onImageLoaded = ::onCreativeRendered,
        onImageFailed = { onCreativeFailed("overlay: $it") },
    )

    override fun mount(view: View, container: ViewGroup): View {
        val resolved = position ?: BannerPosition.fromServer(current?.placement?.position) ?: BannerPosition.BOTTOM
        return mountBanner(view as BannerAdView, container, resolved, style)
    }

    private fun evaluateWindows() {
        val now = playheadMs
        fun isOpen(ad: VideoAd): Boolean {
            val from = ad.trigger.fromMs ?: return false
            return now >= from && now < (ad.trigger.toMs ?: Long.MAX_VALUE)
        }
        // The ad on screen left `pending` when it was shown but keeps its
        // window; consider it first so its own window can't "close" it.
        val nowOpen = current?.takeIf(::isOpen) ?: pending.firstOrNull(::isOpen)
        val open = openWindowAd
        if (open != null && nowOpen?.adId != open.adId) {
            openWindowAd = null
            onWindowClose?.invoke(open)
        }
        if (nowOpen != null && openWindowAd?.adId != nowOpen.adId) {
            openWindowAd = nowOpen
            if (nowOpen.adId != current?.adId) onWindowOpen?.invoke(nowOpen)
        }
    }
}
