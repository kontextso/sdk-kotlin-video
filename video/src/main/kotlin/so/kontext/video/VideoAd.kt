package so.kontext.video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.core.graphics.Insets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.ad.endIfContentStopped
import so.kontext.video.internal.ad.followsContent
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.ad.runAppCodeBeforeShow
import so.kontext.video.internal.ad.startFollowingContent
import so.kontext.video.internal.playback.ContentProgress
import so.kontext.video.internal.rendering.ActiveTimeClock
import so.kontext.video.internal.rendering.AdHostView
import so.kontext.video.internal.rendering.AdPlacement
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.LinearAdActivity
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.rendering.ViewabilityMeter
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.AdPayload
import java.lang.ref.WeakReference

/**
 * One ad, from the server's answer to the end of its presentation. Look at its facts ([price], [format],
 * [durationMillis]), then [load] or [reject], then `show`, then exactly one [VideoAdListener.onAdEnded].
 * Shown at most once. Every call is main-thread.
 */
public class VideoAd internal constructor(
    internal val payload: AdPayload,
    /** `Environment.now()` at the first receipt: the lifetime counts from here. */
    private val receivedAt: Long,
    owner: VideoAds,
) {
    /** The server's id of the ad, for support. */
    public val id: String get() = payload.id

    /** The format it was requested for. */
    public val format: AdFormat get() = payload.format

    /** What it pays, known before anything renders. */
    public val price: Price get() = payload.price

    /**
     * Overlay: how long the SDK keeps it on screen, `null` when it stays until ended; a hint, as a pause of the
     * tracked content ends it earlier. Linear: the video's length. Always `null` for a pause ad. Milliseconds.
     */
    public val durationMillis: Long? get() = payload.durationMillis

    /** `load()` finished: the creative is rendered off screen. */
    public var isLoaded: Boolean = false
        internal set

    /** Loaded and still usable: `show` will not throw `NotLoaded` or `Invalidated`. */
    public val isReady: Boolean get() = isLoaded && !wasShown && invalidationReason == null && !isExpired

    /** Why the ad ended before it was shown, once it did. */
    public var invalidationReason: InvalidationReason? = null
        private set

    /** `show` was accepted once. */
    public var wasShown: Boolean = false
        private set

    /** The ad began to end; `onAdEnded` follows at once for an overlay or pause ad, once its screen is gone for a linear ad. */
    public var hasEnded: Boolean = false
        internal set

    /** The creative ran to its end: an overlay's duration elapsed, a linear video played through. */
    public var isCompleted: Boolean = false
        internal set

    /** The moments on screen. Held until `onAdEnded` has run. */
    public var listener: VideoAdListener? = null

    internal val owner = WeakReference(owner)
    internal val tracker = Tracker(payload.id, payload.tracking).apply {
        contentPlayhead = { this@VideoAd.owner.get()?.playheadMillis ?: 0 }
    }
    internal var loadedBridge: FrameBridge? = null
    private val contextWrapper by lazy { MutableContextWrapper(Environment.context) }
    private var loading: Deferred<Unit>? = null
    internal var loadSignal: CompletableDeferred<Unit>? = null
    private var hostView: AdHostView? = null
    private var extraInsets = Rect()
    private var chosenSize: AdPayload.Size? = null
    private var linearScreen: LinearAdActivity? = null
    private var linearPresentation: LinearAdActivity.Presentation? = null
    private var isPresented = false
    private var finished = false
    private var pendingFinish: EndReason? = null
    private var failure: VideoAdException? = null
    internal var meter: ViewabilityMeter? = null
    internal var videoPlaying = false
    internal var clock: ActiveTimeClock? = null
    internal var contentProgress: ContentProgress? = null
    private var impressionRecorded = false

    internal val isExpired: Boolean get() = Environment.now() - receivedAt >= payload.expiresMillis

    private val bridge: FrameBridge
        get() = loadedBridge ?: FrameBridge(contextWrapper, payload.id, payload.frameUrl, payload.creative == AdPayload.Creative.VIDEO).also { bridge ->
            bridge.onEvent = { event -> handle(event) }
            loadedBridge = bridge
        }

    // region Loading and rejecting

    /**
     * Renders the creative off screen. Returns once it can be shown; throws [VideoAdException]
     * (`CreativeFailed`, `Invalidated`, `Destroyed`, `Disabled`). Idempotent: a second call waits for the first.
     */
    @MainThread
    public suspend fun load() {
        checkUsable()
        if (isLoaded && loading == null) return
        val task = loading ?: startLoading()
        try {
            task.await()
        } catch (error: CancellationException) {
            if (owner.get()?.isDestroyed != false && task.isCancelled) throw VideoAdException.Destroyed()
            throw error
        }
    }

    /** [load] for callers without coroutines: [callback] gets this ad, or the exception, on the main thread. */
    @MainThread
    public fun load(callback: VideoAdsCallback<VideoAd>) {
        Environment.scope.launch {
            try {
                load()
                callback.onSuccess(this@VideoAd)
            } catch (error: VideoAdException) {
                callback.onError(error)
            }
        }
    }

    /** You won't use the ad; the server tells the advertiser why. Works until `show`, loaded or not. */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun reject(reason: RejectReason = RejectReason.NotUsed) {
        withContentPlayhead {
            if (wasShown) throw VideoAdException.AlreadyShown()
            invalidationReason?.let { throw error(it) }
            if (isExpired) {
                invalidate(InvalidationReason.Expired)
                throw VideoAdException.Invalidated(InvalidationReason.Expired)
            }
            Logger.info { "$format ad ${id.take(8)}: rejected ($reason)" }
            // Committed before the beacon: the rejection is final the moment the app asked for it.
            invalidate(InvalidationReason.Rejected)
            tracker.fire(
                "notUsed",
                notUsed = when (reason) {
                    is RejectReason.NotUsed -> Tracker.NotUsed.NotUsedByApp
                    is RejectReason.Outbid -> Tracker.NotUsed.Outbid(reason.winningPrice, reason.winner)
                },
            )
        }
    }

    internal fun checkUsable() {
        val owner = owner.get()
        if (owner == null || owner.isDestroyed) throw VideoAdException.Destroyed()
        Sdk.disabledReason?.let { throw VideoAdException.Disabled(it) }
        invalidationReason?.let { throw error(it) }
        if (isExpired) {
            invalidate(InvalidationReason.Expired)
            throw VideoAdException.Invalidated(InvalidationReason.Expired)
        }
    }

    private fun startLoading(): Deferred<Unit> {
        val owner = owner.get() ?: throw VideoAdException.Destroyed()
        val signal = CompletableDeferred<Unit>().also { loadSignal = it }
        bridge.load()
        val task = owner.scope.async {
            val started = Environment.now()
            try {
                signal.await()
            } catch (error: VideoAdException.CreativeFailed) {
                invalidate(InvalidationReason.CreativeFailed(error.code))
                DebugReporter.event("ad.loadFailed", adId = id, level = DebugReporter.Level.WARNING, state = mapOf("code" to error.code))
                throw error
            } catch (error: VideoAdException) {
                // The ad ended meanwhile: say why in the order the app expects.
                checkUsable()
                throw error
            }
            // Expired while the page loaded: the result is dropped.
            checkUsable()
            val durationMs = Environment.now() - started
            Logger.info { "$format ad ${id.take(8)}: ready in $durationMs ms" }
            DebugReporter.event("ad.loaded", adId = id, state = mapOf("durationMs" to durationMs))
        }
        loading = task
        task.invokeOnCompletion { if (loading === task) loading = null }
        return task
    }

    // endregion

    // region Showing

    /**
     * Shows an overlay or pause ad inside [container], the view the content plays in. Returns once the ad is
     * on screen. [insets] (pixels) keep it clear of your own controls, added to the system bars and cutout
     * that overlap the container; kept through resizes and rotations, counted in `ContainerTooSmall`'s
     * minimum; a full-screen pause ad ignores them. Throws, with nothing shown: `NotLoaded`, `AlreadyShown`,
     * `AnotherAdShowing`, `Invalidated`, `Destroyed`, `Disabled`, `PresenterUnavailable` (the container is not
     * attached, not shown, or has no area), `ContainerTooSmall`, `ContentPaused` (an overlay over paused
     * content; the ad stays ready in these three), `WrongPresentation` (a linear ad).
     */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun show(container: ViewGroup, insets: Insets = Insets.NONE) {
        withContentPlayhead { showInline(container, insets) }
    }

    private fun showInline(container: ViewGroup, insets: Insets) {
        if (format == AdFormat.LINEAR) throw VideoAdException.WrongPresentation()
        checkShowable()
        if (!AdPlacement.canBeSeen(container)) {
            Logger.warning { "ad ${id.take(8)}: cannot show, the container is not on screen or is hidden" }
            throw VideoAdException.PresenterUnavailable()
        }
        extraInsets = Rect(maxOf(0, insets.left), maxOf(0, insets.top), maxOf(0, insets.right), maxOf(0, insets.bottom))
        val density = container.resources.displayMetrics.density
        // Picks the variant, or throws `ContainerTooSmall`.
        chooseSize(container.width, container.height, combinedInsets(AdHostView.systemInsets(container)), density)
        // Nothing throws from here on. Reserved before any of the app's code runs: adding the host calls the
        // container's own hooks. A show from there finds the slot taken; a destroy there ends this ad.
        val bridge = bridge
        reserve()
        contextWrapper.baseContext = container.context
        val webView = bridge.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (hasEnded) return
        bridge.chosenSize = chosenSize
        val host = AdHostView(container.context, webView)
        host.place = { width, height, system -> refit(width, height, system, density) }
        host.onUnfit = {
            if (!hasEnded) {
                Logger.warning { "ad ${id.take(8)}: the container became too small for the ad; ended as interrupted" }
                end(EndReason.INTERRUPTED)
            }
        }
        host.onWindowVisibility = { visible -> clock?.windowVisibilityChanged(visible) }
        hostView = host
        container.addView(host)
        if (!hasEnded && !AdPlacement.canBeSeen(container)) {
            Logger.warning { "ad ${id.take(8)}: the container left the screen as the ad was added; ended as interrupted" }
            end(EndReason.INTERRUPTED)
            return
        }
        endIfContentStopped()
        presented()
    }

    /**
     * Shows a linear ad full screen, in the SDK's own Activity started from [activity]. Throws as
     * `show(container)` does; `PresenterUnavailable` for an Activity that is finishing or destroyed.
     */
    @MainThread
    @Throws(VideoAdException::class)
    public fun show(activity: Activity) {
        withContentPlayhead { showLinear(activity) }
    }

    private fun showLinear(activity: Activity) {
        if (format != AdFormat.LINEAR) throw VideoAdException.WrongPresentation()
        checkShowable()
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.warning { "ad ${id.take(8)}: cannot present, the Activity is finishing" }
            throw VideoAdException.PresenterUnavailable()
        }
        val bridge = bridge
        reserve()
        if (hasEnded) return
        val presentation = object : LinearAdActivity.Presentation {
            override fun attach(activity: LinearAdActivity, root: FrameLayout) {
                if (hasEnded) {
                    activity.isEndingByAd = true
                    activity.finish()
                    return
                }
                linearScreen = activity
                contextWrapper.baseContext = activity
                (bridge.webView.parent as? ViewGroup)?.removeView(bridge.webView)
                root.addView(bridge.webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }

            override fun onScreenShown() = presented()

            override fun onScreenGone(byAd: Boolean) {
                linearScreen = null
                if (byAd) {
                    pendingFinish?.let(::finish)
                } else if (!hasEnded) {
                    Logger.warning { "ad ${id.take(8)}: the linear ad's screen was taken away; ended as interrupted" }
                    end(EndReason.INTERRUPTED)
                }
            }
        }
        linearPresentation = presentation
        LinearAdActivity.start(activity, presentation)
        // A screen that never starts (the system refused it) must not hold the slot forever.
        owner.get()?.scope?.launch {
            delay(presentationTimeoutMillis)
            if (hasEnded || linearScreen != null) return@launch
            Logger.error { "ad ${id.take(8)}: the linear ad's screen did not start; ended as failed" }
            LinearAdActivity.forget(presentation)
            ErrorReporter.report(
                ErrorReporter.Code.PRESENTATION_FAILED,
                ErrorReporter.Stage.PRESENTATION,
                sessionId = owner.get()?.sessionId,
                adId = id,
                recovery = ErrorReporter.Recovery.AD_ENDED_FAILED,
            )
            fail(VideoAdException.PresenterUnavailable())
        }
    }

    /** Ends an overlay or pause ad because you say so; `onAdEnded(DISMISSED)` follows, synchronously. Before `show` and after the end it does nothing. */
    @MainThread
    @Throws(VideoAdException::class)
    public fun dismiss() {
        if (format == AdFormat.LINEAR) throw VideoAdException.WrongPresentation()
        if (!wasShown || hasEnded) return
        Logger.debug { "ad ${id.take(8)}: dismissed by the app" }
        end(EndReason.DISMISSED)
    }

    override fun toString(): String =
        "VideoAd(id=${id.take(8)}, format=$format, price=$price, loaded=$isLoaded, shown=$wasShown, ended=$hasEnded)"

    private fun checkShowable() {
        val contentPaused = runAppCodeBeforeShow()
        if (wasShown) throw VideoAdException.AlreadyShown()
        checkUsable()
        if (Sdk.adOnScreen != null) throw VideoAdException.AnotherAdShowing()
        if (!isLoaded) throw VideoAdException.NotLoaded()
        if (contentPaused) throw VideoAdException.ContentPaused()
    }

    private fun combinedInsets(system: Rect): Rect =
        Rect(system.left + extraInsets.left, system.top + extraInsets.top, system.right + extraInsets.right, system.bottom + extraInsets.bottom)

    /** Picks the variant for the container: the biggest that fits unscaled, else the one shrunk least. Throws `ContainerTooSmall`. */
    private fun chooseSize(width: Int, height: Int, insets: Rect, density: Float) {
        val defaultSize = payload.sizes.firstOrNull()
        if (payload.position == AdPayload.Position.FULLSCREEN || defaultSize == null) return
        val room = AdPlacement.room(width, height, insets) ?: run {
            chosenSize = null
            throw tooSmall(defaultSize, insets, density, "the insets leave no room for")
        }
        val fit = { candidate: AdPayload.Size -> AdPlacement.scale(candidate, room, density) }
        val size = payload.sizes.filter { fit(it) >= 1 }.maxByOrNull { it.width * it.height }
            ?: payload.sizes.filter { fit(it) >= AdPlacement.smallestScale(it) }.maxByOrNull(fit)
            ?: payload.sizes.maxByOrNull(fit)
            ?: defaultSize
        chosenSize = size
        if (AdPlacement.frame(size, payload.position, width, height, insets, density) == null) {
            chosenSize = null
            throw tooSmall(size, insets, density, "a $width×$height px container is too small for")
        }
    }

    private fun tooSmall(size: AdPayload.Size, insets: Rect, density: Float, what: String): VideoAdException {
        val (minWidth, minHeight) = AdPlacement.minimum(size, insets, density)
        Logger.warning {
            "ad ${id.take(8)}: $what the ad's ${size.width}×${size.height} dp${if (size.scalable) " at 50 %" else ", which may not shrink"}; the ad stays ready"
        }
        return VideoAdException.ContainerTooSmall(minWidth, minHeight)
    }

    private fun refit(width: Int, height: Int, system: Rect, density: Float): Rect? {
        val size = chosenSize
        if (payload.position == AdPayload.Position.FULLSCREEN || size == null) return Rect(0, 0, width, height)
        return AdPlacement.frame(size, payload.position, width, height, combinedInsets(system), density)
    }

    /** Runs [work] with the content playhead read once before it, so no beacon of it calls the app's player. */
    internal fun <T> withContentPlayhead(alsoFor: List<String> = emptyList(), work: () -> T): T {
        val asked = tracker.readsContentPlayhead || alsoFor.any(Tracker::asksForContentPlayhead)
        if (tracker.frozenContentPlayhead != null || !asked) return work()
        tracker.frozenContentPlayhead = tracker.lastContentPlayhead
        try {
            val playhead = tracker.contentPlayhead()
            tracker.lastContentPlayhead = playhead
            tracker.frozenContentPlayhead = playhead
            return work()
        } finally {
            tracker.frozenContentPlayhead = null
        }
    }

    private fun reserve() {
        wasShown = true
        Sdk.adOnScreen = this
        owner.get()?.adDidBeginShowing(this)
    }

    private fun presented() {
        withContentPlayhead { presentNow() }
    }

    private fun presentNow() {
        if (hasEnded || isPresented) return
        isPresented = true
        bridge.show()
        Logger.info { "ad ${id.take(8)}: shown" }
        DebugReporter.event("ad.shown", adId = id, state = mapOf("format" to format.wireName))
        startMeter()
        if (format != AdFormat.LINEAR) {
            val clock = ActiveTimeClock(if (format == AdFormat.OVERLAY && !followsContent) durationMillis else null, id) {
                if (hasEnded) return@ActiveTimeClock
                Logger.debug { "ad ${id.take(8)}: overlay duration of ${durationMillis ?: 0} ms on screen elapsed" }
                isCompleted = true
                end(EndReason.COMPLETED)
            }
            this.clock = clock
            clock.start(visible = hostView?.windowVisibility == View.VISIBLE)
            startFollowingContent()
        }
        // At `ad.show`: creativeView and the AdChoices icon's view; the impression too for an image or HTML
        // creative, while a video's waits for its first frame (`video.start`). Every beacon may read the app's
        // player, which may end the ad: nothing more after that.
        if (hasEnded) return
        tracker.fire("creativeView")
        if (hasEnded) return
        tracker.fire("iconView")
        if (hasEnded) return
        if (payload.creative != AdPayload.Creative.VIDEO) recordImpression()
    }

    // endregion

    // region Measurement

    internal fun recordImpression() {
        if (impressionRecorded || hasEnded) return
        impressionRecorded = true
        tracker.fire("impression")
        Logger.info { "ad ${id.take(8)}: impression" }
        if (hasEnded) return
        listener?.onAdImpression(this)
    }

    private fun startMeter() {
        if (hasEnded || meter != null) return
        val webView = bridge.webView
        val meter = ViewabilityMeter(webView, if (payload.creative == AdPayload.Creative.VIDEO) 2_000 else 1_000)
        // MRC: two seconds of the video played. Exposure is measured from show, for the page.
        meter.countsTime = payload.creative != AdPayload.Creative.VIDEO || videoPlaying
        meter.onExposure = { percentage, visible ->
            val density = webView.resources.displayMetrics.density.toDouble()
            loadedBridge?.exposure(percentage, visible.left / density, visible.top / density, visible.width() / density, visible.height() / density)
        }
        meter.onVerdict = { verdict ->
            withContentPlayhead { tracker.fire(verdict.event) }
            Logger.info { "ad ${id.take(8)}: ${verdict.event}" }
        }
        // The app took the view away (navigated off without dismiss()): the ad ends, or it would block every show.
        meter.onDetached = {
            if (!hasEnded) {
                Logger.warning { "ad ${id.take(8)}: its view left the window; ended as interrupted" }
                end(EndReason.INTERRUPTED)
            }
        }
        this.meter = meter
        meter.start()
    }

    // endregion

    // region Ending

    internal fun fail(error: VideoAdException) {
        if (!wasShown || hasEnded) return
        failure = error
        end(EndReason.FAILED)
    }

    internal fun end(reason: EndReason) {
        withContentPlayhead { endNow(reason) }
    }

    private fun endNow(reason: EndReason) {
        if (!wasShown || hasEnded) return
        hasEnded = true
        if (reason == EndReason.FAILED) failure?.let { listener?.onAdFailed(this, it) }
        // `[KONTEXT_VIEWDURATION]` is the active time, not the wall clock.
        val activeTime = clock?.stop() ?: 0
        meter?.stop()
        meter = null
        loadedBridge?.hide()
        loadedBridge = null
        hostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        hostView = null
        linearPresentation?.let(LinearAdActivity::forget)
        var dismissing = false
        val screen = linearScreen
        linearScreen = null
        if (screen != null && reason != EndReason.INTERRUPTED && !screen.isFinishing && !screen.isDestroyed) {
            // `onAdEnded` waits for the screen to be gone, so the app can start its next one from it.
            dismissing = true
            pendingFinish = reason
            screen.isEndingByAd = true
            screen.finish()
            @Suppress("DEPRECATION")
            screen.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            // A screen the system never takes down: the app hears `onAdEnded` anyway.
            Environment.scope.launch {
                delay(dismissalTimeoutMillis)
                if (!finished) {
                    Logger.error { "ad ${id.take(8)}: the linear ad's screen did not close in ${dismissalTimeoutMillis / 1000} s; onAdEnded delivered anyway" }
                    finish(reason)
                }
            }
        }
        if (format != AdFormat.LINEAR) tracker.fire("overlayViewDuration", viewDurationMillis = activeTime)
        Logger.info { "ad ${id.take(8)}: ended ($reason)" }
        DebugReporter.event("ad.ended", adId = id, state = mapOf("reason" to reason.name.lowercase()))
        if (!dismissing) finish(reason)
    }

    private fun finish(reason: EndReason) {
        if (finished) return
        finished = true
        pendingFinish = null
        if (Sdk.adOnScreen === this) Sdk.adOnScreen = null
        owner.get()?.adDidEnd(this)
        val listener = listener
        // The ad lets its listener go: an Activity held there must not outlive the ad.
        this.listener = null
        listener?.onAdEnded(this, reason)
    }

    // endregion

    // region Clicks

    internal fun open(url: String, fallback: String?) {
        val context = Environment.context ?: return
        if (startView(context, url)) return
        if (fallback != null && startView(context, fallback)) return
        Logger.warning { "ad ${id.take(8)}: nothing could open the click-through ${Logger.describe(url)}" }
    }

    private fun startView(context: android.content.Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // endregion

    // region Ending before show

    internal fun invalidate(reason: InvalidationReason) {
        if (wasShown || invalidationReason != null) return
        invalidationReason = reason
        Logger.debug { "$format ad ${id.take(8)} ended before show: $reason" }
        DebugReporter.event("ad.invalidated", adId = id, state = mapOf("reason" to reason.toString()))
        loadedBridge?.hide()
        loadedBridge = null
        loadSignal?.let {
            loadSignal = null
            it.completeExceptionally(error(reason))
        }
        owner.get()?.adDidLeave(this)
    }

    private fun error(reason: InvalidationReason): VideoAdException =
        if (reason == InvalidationReason.Destroyed) VideoAdException.Destroyed() else VideoAdException.Invalidated(reason)

    // endregion

    internal companion object {
        /** How long a linear ad's screen may take to start, and to close. Tests shorten them. */
        var presentationTimeoutMillis = 5_000L
        var dismissalTimeoutMillis = 2_000L
    }

    /** What an ad pays: a CPM in a currency, and whether that figure is a clearing price or a forecast. */
    public class Price internal constructor(
        /** Per 1,000 impressions. */
        public val value: Double,
        /** ISO 4217. */
        public val currency: String,
        /** Whether [value] is a DSP's clearing price or a forecast. */
        public val precision: Precision,
    ) {
        /** How firm [value] is. */
        public enum class Precision {
            /** A DSP's clearing price. */
            EXACT,

            /** Our own or house demand, a forecast. */
            ESTIMATED,
        }

        override fun equals(other: Any?): Boolean =
            other is Price && value == other.value && currency == other.currency && precision == other.precision

        override fun hashCode(): Int = listOf(value, currency, precision).hashCode()

        override fun toString(): String = "Price($value $currency, $precision)"
    }

    /** Why the app did not use an ad; the server tells the DSP. */
    public sealed class RejectReason {
        /** The app does not use it, for any reason: the moment passed, the viewer left, the app's own choice. */
        public object NotUsed : RejectReason() {
            override fun toString(): String = "NotUsed"
        }

        /** Another network won the publisher's auction; [winningPrice] (CPM) and [winner] (the network, in the app's own words) when known. */
        public class Outbid @JvmOverloads constructor(
            public val winningPrice: Double? = null,
            public val winner: String? = null,
        ) : RejectReason() {
            override fun equals(other: Any?): Boolean = other is Outbid && winningPrice == other.winningPrice && winner == other.winner

            override fun hashCode(): Int = listOf(winningPrice, winner).hashCode()

            override fun toString(): String = "Outbid(winningPrice=$winningPrice, winner=$winner)"
        }
    }

    /** Why an ad ended before it was shown. Found out at the next call (`format`, `load`, `show`, `reject`), which throws it. */
    public sealed class InvalidationReason {
        /** The ad's lifetime ran out (or the server replaced it, which it does only after that). */
        public object Expired : InvalidationReason() {
            override fun toString(): String = "Expired"
        }

        /** The app called `reject()`. */
        public object Rejected : InvalidationReason() {
            override fun toString(): String = "Rejected"
        }

        /** The `VideoAds` was destroyed. */
        public object Destroyed : InvalidationReason() {
            override fun toString(): String = "Destroyed"
        }

        /** The creative failed to load, or died while the ad waited to be shown. [code] is the VAST error code the SDK fired. */
        public class CreativeFailed(public val code: Int) : InvalidationReason() {
            override fun equals(other: Any?): Boolean = other is CreativeFailed && code == other.code

            override fun hashCode(): Int = code

            override fun toString(): String = "CreativeFailed($code)"
        }
    }

    /** A tap on the ad. It carries no URL: the SDK has already opened the target. */
    public class Click internal constructor(
        /** Where the viewer tapped. */
        public val area: Area,
    ) {
        /** Where the viewer tapped. */
        public sealed class Area {
            /** The creative itself: the image, the video, the card. */
            public object Creative : Area() {
                override fun toString(): String = "Creative"
            }

            /** A linear ad's end card, after the video completed. */
            public object EndCard : Area() {
                override fun toString(): String = "EndCard"
            }

            /** A label of the page's own (`cta`, `logo`, ...), passed through unchanged. */
            public class Other(public val label: String) : Area() {
                override fun equals(other: Any?): Boolean = other is Other && label == other.label

                override fun hashCode(): Int = label.hashCode()

                override fun toString(): String = "Other($label)"
            }

            internal companion object {
                /** From the page's `area`: absent is the creative. */
                fun from(raw: String?): Area = when (raw) {
                    null, "creative" -> Creative
                    "endCard" -> EndCard
                    else -> Other(raw)
                }
            }
        }

        override fun equals(other: Any?): Boolean = other is Click && area == other.area

        override fun hashCode(): Int = area.hashCode()

        override fun toString(): String = "Click($area)"
    }

    /**
     * How an ad on screen ended: who or what took it off the screen. Whether the creative ran to its end is a
     * separate fact, `VideoAd.isCompleted`: a linear ad whose end card the viewer closes is [CLOSED] with
     * `isCompleted == true`.
     */
    public enum class EndReason {
        /** The ad ended by itself: an overlay's duration elapsed; a linear ad without an end card ended after its video completed. */
        COMPLETED,

        /** The viewer tapped a linear ad's Skip (or close) before the video ended. */
        SKIPPED,

        /** The viewer tapped the ad's close control: an overlay's or pause card's ✕ (the content stays paused), or a linear ad's end card ✕. */
        CLOSED,

        /** The viewer tapped Resume on a full-screen pause ad. */
        RESUMED,

        /** The ad broke after `show` returned; `onAdFailed` came first. */
        FAILED,

        /**
         * The presentation disappeared for a reason the SDK did not cause: the container left the window or
         * became too small for the ad, the linear ad's screen was destroyed under it. For an overlay with a
         * tracked player, also the content pausing, ending or moving to another item.
         */
        INTERRUPTED,

        /** The app called `dismiss()`. */
        DISMISSED,

        /** The app called `destroy()` on the `VideoAds` while the ad was on screen. */
        DESTROYED,
    }
}
