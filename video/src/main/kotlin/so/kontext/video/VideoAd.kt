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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
import so.kontext.video.internal.support.ActivityTracker
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.AdPayload
import java.lang.ref.WeakReference

/**
 * One ad, from the server's answer to the end of its presentation. Look at its facts ([price], [format],
 * [durationMillis]), then [load] or [reject], then `show`, then exactly one [VideoAdListener.onAdEnded].
 * Shown at most once. Every call is main-thread, except [load], which may be called from any dispatcher.
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
    internal val contextWrapper by lazy { MutableContextWrapper(Environment.context) }
    private var loading: Deferred<Unit>? = null
    internal var loadSignal: CompletableDeferred<Unit>? = null
    private var hostView: AdHostView? = null
    private var extraInsets = Rect()
    private var chosenSize: AdPayload.Size? = null
    private var linearScreen: LinearAdActivity? = null
    internal var linearPresentation: LinearAdActivity.Presentation? = null
        private set

    /** The ad is on screen: `show` attached it and nothing ended it during the attach. */
    internal var isPresented = false
        private set
    private var finished = false
    private var pendingFinish: EndReason? = null
    private var failure: VideoAdException? = null
    internal var meter: ViewabilityMeter? = null
    internal var videoPlaying = false
    internal var clock: ActiveTimeClock? = null
    internal var contentProgress: ContentProgress? = null

    /** A pause ad that follows the content saw it paused while on screen: the content playing again ends it. */
    internal var contentWasPaused = false
    private var impressionRecorded = false

    /** Follows the Activity the ad depends on while it is on screen; removed when the ad ends. */
    private var activityListener: ((Activity, ActivityTracker.Event) -> Unit)? = null

    internal val isExpired: Boolean get() = Environment.now() - receivedAt >= payload.expiresMillis

    private val bridge: FrameBridge
        // A linear ad's duration is its video's length; an overlay's is its time on screen, not the video's.
        get() = loadedBridge ?: makeBridge(
            contextWrapper,
            payload.id,
            payload.frameUrl,
            payload.creative == AdPayload.Creative.VIDEO,
            payload.durationMillis.takeIf { format == AdFormat.LINEAR },
        ).also { bridge ->
            bridge.onEvent = { event -> handle(event) }
            loadedBridge = bridge
        }

    // region Loading and rejecting

    /**
     * Renders the creative off screen. Returns once it can be shown; throws [VideoAdException]
     * (`CreativeFailed`, `Invalidated`, `Destroyed`, `Disabled`). Idempotent: a second call waits for the first.
     * Callable from any dispatcher: the work runs on the main thread.
     */
    public suspend fun load(): Unit = withContext(Dispatchers.Main.immediate) { loadOnMain() }

    private suspend fun loadOnMain() {
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
            // The app's own onSuccess may throw (a show that fails): that is not a failed load.
            try {
                load()
            } catch (error: VideoAdException) {
                callback.onError(error)
                return@launch
            }
            callback.onSuccess(this@VideoAd)
        }
    }

    /** You won't use the ad; the server tells the advertiser why. Works until `show`, loaded or not. */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun reject(reason: RejectReason = RejectReason.NotUsed) {
        Sdk.checkMainThread("VideoAd.reject()")
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
        val bridge = try {
            bridge
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // The WebView is missing or updating, or refused the bridge: the app hears a creative that failed
            // to load, never an exception of the platform's.
            throw bridgeFailed(error)
        }
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

    private fun bridgeFailed(error: Throwable): VideoAdException {
        val code = if (payload.creative == AdPayload.Creative.VIDEO) 400 else 500
        Logger.warning { "ad ${id.take(8)}: no WebView for the creative (${error.javaClass.simpleName}), VAST error $code" }
        tracker.fire("error", errorCode = code)
        ErrorReporter.report(
            ErrorReporter.Code.FRAME_LOAD_FAILED,
            ErrorReporter.Stage.LOAD,
            sessionId = owner.get()?.sessionId,
            adId = id,
            vastCode = code,
            recovery = ErrorReporter.Recovery.AD_DROPPED_BEFORE_SHOW,
        )
        invalidate(InvalidationReason.CreativeFailed(code))
        DebugReporter.event("ad.loadFailed", adId = id, level = DebugReporter.Level.WARNING, state = mapOf("code" to code))
        return VideoAdException.CreativeFailed(code)
    }

    // endregion

    // region Showing

    /**
     * Shows an overlay or pause ad inside [container], the view the content plays in. Returns once the ad is
     * on screen. [insets] (pixels) keep it clear of your own controls, added to the system bars and cutout
     * that overlap the container; kept through resizes and rotations, counted in `ContainerTooSmall`'s
     * minimum; a full-screen pause ad ignores them: it covers the whole container, its page inside the
     * system bars and cutout. Throws, with nothing shown: `NotLoaded`, `AlreadyShown`, `AnotherAdShowing`,
     * `Invalidated`, `Destroyed`, `Disabled`, `PresenterUnavailable` (the container is not attached, not shown,
     * has no area, lies entirely outside its window such as a pager's neighbour page, its Activity is stopped or
     * the app is in the background, or it refuses a child: a ScrollView that has one, a ListView),
     * `ContainerTooSmall`, `ContentPaused` (an overlay over paused content; the ad stays ready in these three),
     * `WrongPresentation` (a linear ad).
     */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun show(container: ViewGroup, insets: Insets = Insets.NONE) {
        Sdk.checkMainThread("VideoAd.show(container)")
        withContentPlayhead { showInline(container, insets) }
    }

    private fun showInline(container: ViewGroup, insets: Insets) {
        if (format == AdFormat.LINEAR) throw VideoAdException.WrongPresentation()
        checkShowable()
        if (!AdPlacement.canBeSeen(container)) {
            Logger.warning { "ad ${id.take(8)}: cannot show, the container is not on screen or is hidden" }
            throw VideoAdException.PresenterUnavailable()
        }
        // Attached and laid out is not on screen: a pager's neighbour page, a view scrolled away, a screen behind
        // another or an app in the background. The impression would count for nobody: refused, the ad ready.
        if (!isOnScreen(container)) throw VideoAdException.PresenterUnavailable()
        extraInsets = Rect(maxOf(0, insets.left), maxOf(0, insets.top), maxOf(0, insets.right), maxOf(0, insets.bottom))
        val density = container.resources.displayMetrics.density
        // Picks the variant, or throws `ContainerTooSmall`, for where the host will be: the container's content
        // area, inside its padding, less the bars that reach into it and the app's insets. In the container's
        // pixels, so the minimum is a container size.
        val system = AdHostView.systemInsets(container)
        val contentArea = Rect(
            maxOf(container.paddingLeft, system.left),
            maxOf(container.paddingTop, system.top),
            maxOf(container.paddingRight, system.right),
            maxOf(container.paddingBottom, system.bottom),
        )
        chooseSize(container.width, container.height, combinedInsets(contentArea), density)
        // Reserved before any of the app's code runs: adding the host calls the container's own hooks. A show
        // from there finds the slot taken; a destroy there ends this ad. The container may refuse the host (a
        // ScrollView with its child, a ListView) or its hook may throw: then nothing happened, see below.
        val bridge = bridge
        val wasHeld = owner.get()?.ads?.get(format) === this
        reserve()
        try {
            contextWrapper.baseContext = container.context
            val webView = bridge.webView
            (webView.parent as? ViewGroup)?.removeView(webView)
            if (hasEnded) return
            bridge.chosenSize = chosenSize
            // A full-screen pause ad's page stops at the bars (see refit): its host fills the strips behind
            // them and takes their touches, so the paused frame and the player stay covered as on iOS.
            val host = AdHostView(container.context, webView, coversWhole = payload.position == AdPayload.Position.FULLSCREEN)
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
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            unreserve(container, wasHeld, error)
            // An ad its owner ended meanwhile (a destroy in the container's hook) has had its onAdEnded: show
            // was accepted and is over. Otherwise nothing was shown and nothing spent.
            if (hasEnded) return
            throw VideoAdException.PresenterUnavailable()
        }
        // A destroy in the container's hook ended the ad and took its host.
        val host = hostView ?: return
        // The container's own hook may have removed the host, or taken the container off screen.
        if (!hasEnded && (host.parent !== container || host.visibility != View.VISIBLE || host.alpha <= 0.01f || !AdPlacement.canBeSeen(container))) {
            Logger.warning { "ad ${id.take(8)}: the ad left the screen as it was added; ended as interrupted" }
            end(EndReason.INTERRUPTED)
            return
        }
        endIfContentStopped()
        presented()
    }

    /** Some of [container] is inside its window, its Activity is started and the app is in front. Says why not. */
    private fun isOnScreen(container: ViewGroup): Boolean {
        val why = when {
            ViewabilityMeter.exposure(container).first <= 0 -> "no part of the container is inside its window (a neighbour page, scrolled away)"
            !Environment.isAppInForeground() -> "the app is not in front"
            container.activity?.let { it.isFinishing || it.isDestroyed || ActivityTracker.isStarted(it) == false } == true -> "its Activity is not visible"
            else -> return true
        }
        Logger.warning { "ad ${id.take(8)}: cannot show, $why; the ad stays ready" }
        return false
    }

    /**
     * Another Activity of the app covers the one holding the container (it stopped while the app stays in
     * front): the ad ends, interrupted, as on iOS, where a full-screen cover takes the presenter's view out of
     * its window. The app going to the background only freezes the ad's clock; a stop for a configuration
     * change recreates the Activity, and the container leaving the window ends the ad there.
     */
    private fun followHoldingActivity(holder: Activity) = follow(holder) { activity, event ->
        if (event == ActivityTracker.Event.STOPPED && !activity.isChangingConfigurations && Environment.isAppInForeground()) {
            Logger.warning { "ad ${id.take(8)}: another screen of the app covers the ad's; ended as interrupted" }
            end(EndReason.INTERRUPTED)
        }
    }

    /**
     * The Activity that started a linear ad finishes under it (the app closed that screen): the ad ends,
     * interrupted, and its screen closes, as on iOS when the presenter is dismissed. Not a destroy without
     * finishing (a configuration change, or the system reclaiming a screen behind): the app comes back to it
     * when the ad ends.
     */
    private fun followStartingActivity(starter: Activity) = follow(starter) { activity, event ->
        if (event == ActivityTracker.Event.DESTROYED && activity.isFinishing) {
            Logger.warning { "ad ${id.take(8)}: the Activity that started the linear ad finished; ended as interrupted" }
            end(EndReason.INTERRUPTED)
        }
    }

    /** Hears [activity]'s stop and destroy until the ad ends. */
    private fun follow(activity: Activity, onEvent: (Activity, ActivityTracker.Event) -> Unit) {
        val target = WeakReference(activity)
        val listener: (Activity, ActivityTracker.Event) -> Unit = { which, event -> if (which === target.get() && !hasEnded) onEvent(which, event) }
        activityListener?.let(ActivityTracker::removeListener)
        activityListener = listener
        ActivityTracker.addListener(listener)
    }

    /** The Activity the view is in, through any context wrappers. */
    private val View.activity: Activity?
        get() {
            var context: android.content.Context? = context
            while (context is android.content.ContextWrapper) {
                if (context is Activity) return context
                context = context.baseContext
            }
            return null
        }

    /**
     * Whether [activity] is visible to the viewer: started, resumed or not, and not in picture-in-picture.
     * Started is enough: Android starts a screen from `onStart`, a result or permission callback or
     * `onNewIntent` (all before `onResume`), and it comes in front. The tracker knows from the Activity's first
     * event; one it has not seen falls back to the Activity's own lifecycle, else is assumed visible (the
     * presentation timeout still catches a screen that never comes).
     */
    private fun isVisibleToViewer(activity: Activity): Boolean {
        if (activity.isInPictureInPictureMode) return false
        return ActivityTracker.isStarted(activity)
            ?: (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)
            ?: true
    }

    /**
     * Shows a linear ad full screen, in the SDK's own Activity started from [activity]. Throws as
     * `show(container)` does; `PresenterUnavailable` for an Activity that is finishing, destroyed, stopped (not
     * visible) or in picture-in-picture (the ad stays ready; try again once it is visible). A started Activity
     * that is not resumed yet is fine: `onStart`, a result or permission callback, `onNewIntent`.
     */
    @MainThread
    @Throws(VideoAdException::class)
    public fun show(activity: Activity) {
        Sdk.checkMainThread("VideoAd.show(activity)")
        withContentPlayhead { showLinear(activity) }
    }

    private fun showLinear(activity: Activity) {
        if (format != AdFormat.LINEAR) throw VideoAdException.WrongPresentation()
        checkShowable()
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.warning { "ad ${id.take(8)}: cannot present, the Activity is finishing" }
            throw VideoAdException.PresenterUnavailable()
        }
        // The system would not start the screen from there (or not where the viewer sees it), and the bid
        // would be spent on a screen that never comes: refused while the ad is still usable.
        if (!isVisibleToViewer(activity)) {
            Logger.warning { "ad ${id.take(8)}: cannot present, the Activity is not visible (stopped, or in picture-in-picture)" }
            throw VideoAdException.PresenterUnavailable()
        }
        val bridge = bridge
        reserve()
        if (hasEnded) return
        followStartingActivity(activity)
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
        // Any launch that throws (the Activity stripped from the merged manifest, a refused start, an app's own
        // startActivity override or a system state that throws something else) is the same failure.
        val started = try {
            LinearAdActivity.start(activity, presentation)
            true
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            Logger.error { "ad ${id.take(8)}: the linear ad's screen could not start (${error.javaClass.simpleName})" }
            false
        }
        // A screen that never starts (the system refused it, or the launch threw) must not hold the slot
        // forever. A launch that threw takes the same path at once, after show returns, as Swift's refused
        // presentation does: show was accepted, so the ad ends with onAdFailed and onAdEnded(FAILED). On the
        // SDK's own scope when the owner is gone, so the slot is freed whatever holds the ad.
        (owner.get()?.scope ?: Environment.scope).launch {
            if (started) delay(presentationTimeoutMillis) else yield()
            // Presented, not merely created: a screen that attached but never came to the front (started, never
            // resumed) would otherwise hold the slot for good.
            if (hasEnded || isPresented) return@launch
            Logger.error {
                "ad ${id.take(8)}: the linear ad's screen ${if (linearScreen == null) "did not start" else "never came to the front"}; ended as failed"
            }
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
        Sdk.checkMainThread("VideoAd.dismiss()")
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

    /** The system's and the app's insets together; saturated, so a huge app inset cannot wrap around to a small one. */
    private fun combinedInsets(system: Rect): Rect {
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Rect(add(system.left, extraInsets.left), add(system.top, extraInsets.top), add(system.right, extraInsets.right), add(system.bottom, extraInsets.bottom))
    }

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
        // A full-screen pause ad clears the system bars and the cutout: Android's WebView gives the page no
        // safe-area insets, so a page laid out edge to edge would put its controls under the clock. The host
        // still covers the whole container behind the bars.
        if (payload.position == AdPayload.Position.FULLSCREEN) return clearOfBars(width, height, system)
        val size = chosenSize ?: return Rect(0, 0, width, height)
        return AdPlacement.frame(size, payload.position, width, height, combinedInsets(system), density)
    }

    /** The container less the system bars and cutout that overlap it; the whole container when they leave no room. */
    private fun clearOfBars(width: Int, height: Int, system: Rect): Rect {
        val frame = Rect(system.left, system.top, width - system.right, height - system.bottom)
        return if (frame.width() > 0 && frame.height() > 0) frame else Rect(0, 0, width, height)
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

    /** The container refused the host: the reservation is undone, the host and the page released, the ad held again. */
    private fun unreserve(container: ViewGroup, wasHeld: Boolean, error: Exception) {
        Logger.warning { "ad ${id.take(8)}: the container refused the ad (${error.javaClass.simpleName}); not shown, the ad stays ready" }
        val host = hostView
        hostView = null
        if (host != null) {
            // A container that refuses a child may refuse its removal too.
            runCatching { (host.parent as? ViewGroup)?.removeView(host) }
            if (host.parent === container) runCatching { container.removeViewInLayout(host) }
            host.removeAllViews()
        }
        loadedBridge?.webView?.let { webView -> runCatching { (webView.parent as? ViewGroup)?.removeView(webView) } }
        Environment.context?.let { contextWrapper.baseContext = it }
        if (hasEnded) return
        wasShown = false
        if (Sdk.adOnScreen === this) Sdk.adOnScreen = null
        owner.get()?.adDidNotShow(this, wasHeld)
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
            hostView?.activity?.let(::followHoldingActivity)
        }
        // At `ad.show`: creativeView and the AdChoices icon's view; the impression too for an image or HTML
        // creative, while a video's waits for its first frame (`video.start`). The beacons use the playhead read
        // before this block and call no app code, so one check suffices: what ran above (the meter, following
        // the content, which reads the player again, and the Activity) may have ended the ad.
        if (hasEnded) return
        tracker.fire("creativeView")
        tracker.fire("iconView")
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
        activityListener?.let(ActivityTracker::removeListener)
        activityListener = null
        if (reason == EndReason.FAILED) failure?.let { listener?.onAdFailed(this, it) }
        // `[KONTEXT_VIEWDURATION]` is the active time, not the wall clock.
        val activeTime = clock?.stop() ?: 0
        // The stopped clock's callback reaches the owner's playback: an ended ad an app keeps must not keep its VideoAds.
        clock?.onActivate = null
        clock = null
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
        // Every way the ad ends closes a screen still up, an interruption too: its page is gone and Back does
        // nothing there. A screen the system took away has already cleared `linearScreen`.
        if (screen != null && !screen.isFinishing && !screen.isDestroyed) {
            // `onAdEnded` waits for the screen to be gone, so the app can start its next one from it.
            dismissing = true
            pendingFinish = reason
            screen.isEndingByAd = true
            screen.finish()
            @Suppress("DEPRECATION")
            screen.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            // `onAdEnded` follows the screen's own signal (its stop as it finishes, or its destroy). A screen the
            // system never takes down is a fault: after a cap well past any transition it is reported, and the
            // app hears `onAdEnded` anyway, once.
            Environment.scope.launch {
                delay(dismissalTimeoutMillis)
                if (!finished) {
                    Logger.error { "ad ${id.take(8)}: the linear ad's screen did not close in ${dismissalTimeoutMillis / 1000} s; onAdEnded delivered anyway" }
                    DebugReporter.event("ad.dismissalTimedOut", adId = id, level = DebugReporter.Level.WARNING)
                    ErrorReporter.report(
                        ErrorReporter.Code.DISMISSAL_TIMED_OUT,
                        ErrorReporter.Stage.PRESENTATION,
                        sessionId = owner.get()?.sessionId,
                        adId = id,
                        recovery = ErrorReporter.Recovery.DID_END_DELIVERED,
                    )
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
        // An ended ad keeps its facts, not its presentation: an app that holds it holds no Activity or WebView.
        linearPresentation = null
        linearScreen = null
        Environment.context?.let { contextWrapper.baseContext = it }
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

    /**
     * Opens [url] as a browser opens a link: a BROWSABLE intent with no component or selector, so only what
     * declares itself reachable from the web starts (the advertiser's app links do, and the host app's own:
     * which links an ad may carry is the ad server's decision). An app the system cannot see or that is
     * absent surfaces as `ActivityNotFoundException`, and the fallback opens.
     */
    private fun startView(context: android.content.Context, url: String): Boolean {
        if (!FrameBridge.isAllowed(url)) {
            Logger.warning { "ad ${id.take(8)}: the click-through ${Logger.describe(url)} is not a link that may open" }
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = null
        intent.selector = null
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
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
        // It will never be shown, so never reach onAdEnded, which lets the listener go: an Activity held there
        // must not stay with an ad the app keeps.
        listener = null
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
        /**
         * How long a linear ad's screen may take to start, and the cap on its closing: a watchdog that reports
         * a screen the system never takes down. Tests shorten them.
         */
        var presentationTimeoutMillis = 5_000L
        var dismissalTimeoutMillis = 10_000L

        /** Makes an ad's bridge; tests make one that throws, as a device without a working WebView does. */
        var makeBridge: (android.content.Context, String, String, Boolean, Long?) -> FrameBridge = ::FrameBridge
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

        // 0.0 and -0.0 are equal, so they hash alike; a price is never NaN.
        override fun hashCode(): Int = listOf(if (value == 0.0) 0.0 else value, currency, precision).hashCode()

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

        /** The viewer tapped Resume on a full-screen pause ad, the ad's own control. Content resumed elsewhere ends it as [INTERRUPTED]. */
        RESUMED,

        /** The ad broke after `show` returned; `onAdFailed` came first. */
        FAILED,

        /**
         * The presentation disappeared for a reason the SDK did not cause: the container left the window or
         * became too small for the ad, another screen of the app covered the container's (its Activity stopped
         * while the app stays in front; the app going to the background only pauses the ad), the linear ad's
         * screen was destroyed under it or the Activity that started it finished. For an overlay with a
         * tracked player, also the content pausing, ending or moving to another item; for a pause ad with a
         * tracked player, the content playing again (resumed outside the ad: the media notification, a headset).
         */
        INTERRUPTED,

        /** The app called `dismiss()`. */
        DISMISSED,

        /** The app called `destroy()` on the `VideoAds` while the ad was on screen. */
        DESTROYED,
    }
}
