package so.kontext.video

import android.app.Activity
import android.graphics.Rect
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.graphics.Insets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import so.kontext.video.internal.content.ContentPlayback
import so.kontext.video.internal.content.ContentRule
import so.kontext.video.internal.content.ContentTimeClock
import so.kontext.video.internal.content.OverlayRule
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.IntegrationGuard
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.measurement.Tracker
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.platform.LinkOpener
import so.kontext.video.internal.screen.AdScreen
import so.kontext.video.internal.screen.InlineScreen
import so.kontext.video.internal.screen.LinearAdActivity
import so.kontext.video.internal.screen.LinearScreen
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.internal.screen.activity
import so.kontext.video.internal.state.AdLifecycle.Phase
import so.kontext.video.internal.state.AdState
import so.kontext.video.internal.state.SdkState
import java.lang.ref.WeakReference

/**
 * One ad, from the server's answer to the end of its time on screen. Look at its facts ([price], [format],
 * [durationMillis]), then [load] or [reject], then `show`, then exactly one [VideoAdListener.onAdEnded].
 * Shown at most once. Every call is main-thread, except [load], which may be called from any dispatcher.
 */
public class VideoAd internal constructor(
    internal val payload: AdPayload,
    /** `Environment.now()` at the first receipt: the lifetime counts from here. */
    private val receivedAt: Long,
    owner: VideoAds,
) {
    // Format differences live in two places, not in branches here:
    // - how the ad follows the content under it: its ContentRule (OverlayRule, PauseRule, NoContentRule), made
    //   once from the format;
    // - where it is shown: its AdScreen (InlineScreen for an overlay or pause ad, LinearScreen for
    //   a linear ad), made by show.
    // A few facts of the linear format itself stay here: the two show guards, dismiss, the page's video duration,
    // and a close before `complete` being a skip (closeByPage). A full-screen pause ad is a placement of the
    // inline screen (its host covers the container), not a format of its own.
    //
    // Everything of the ad is in this class: no extension of VideoAd lives elsewhere. Its parts are:
    // - internal/state/AdState.kt: every fact of the ad's life (read through [state]);
    // - internal/state/AdLifecycle.kt: its phases and their legal moves;
    // - internal/frame/FrameBridge.kt: its page; the page's events come back to [handle];
    // - internal/measurement/Tracker.kt: its beacons ([tracker]);
    // - internal/content/ContentRule.kt: how it follows the content ([contentRule]);
    // - internal/screen/AdScreen.kt, InlineScreen.kt, LinearScreen.kt: where it is shown (and, inline, where the
    //   page goes in its host).

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
    public val isLoaded: Boolean get() = state.isLoaded

    /** Loaded and still usable: `show` will not throw `NotLoaded`, `Invalidated` or `Disabled`. */
    public val isReady: Boolean get() = isLoaded && !wasShown && invalidationReason == null && !isExpired && SdkState.current.disabledReason == null

    /** Why the ad ended before it was shown, once it did. */
    public val invalidationReason: InvalidationReason? get() = state.invalidationReason

    /** `show` was accepted once. */
    public val wasShown: Boolean get() = state.wasShown

    /** The ad began to end; `onAdEnded` follows at once for an overlay or pause ad, once its screen is gone for a linear ad. */
    public val hasEnded: Boolean get() = state.hasEnded

    /** The creative ran to its end: an overlay's duration elapsed, a linear video played through. */
    public val isCompleted: Boolean get() = state.isCompleted

    /** The moments on screen. Held until `onAdEnded` has run. */
    public var listener: VideoAdListener? = null

    internal val owner = WeakReference(owner)
    internal val tracker = Tracker(payload.id, payload.tracking).apply {
        contentPlayhead = { this@VideoAd.owner.get()?.playheadMillis ?: 0 }
    }

    /** The ad's page, if any: made when loading starts, released when the ad ends. Reading it never makes one. */
    internal var loadedBridge: FrameBridge? = null
    private var loading: Deferred<Unit>? = null
    private var loadSignal: CompletableDeferred<Unit>? = null

    /**
     * Where the ad is shown, from `show` to `onAdEnded` (see [AdScreen]): inline in the app's container, or its
     * own Activity; it holds the meter. The app's one slot is [ScreenSlot]'s.
     */
    internal var screen: AdScreen? = null
        private set

    /** The registration that hands a linear ad to its Activity. */
    internal val linearCallbacks: LinearAdActivity.Callbacks? get() = screen as? LinearScreen

    /** Every fact of the ad's life (see [AdState]): every public flag above reads from it, live. */
    internal val state = AdState()
    internal val meter: ViewabilityMeter? get() = screen?.meter
    internal val onScreenClock: OnScreenTimeClock? get() = (screen as? InlineScreen)?.onScreenClock

    /** How the ad follows the content under it, by format: see [ContentRule]. */
    internal val contentRule: ContentRule = ContentRule.of(this)

    /** The playback of the ad's `VideoAds`, while it has one: what the content rules read the content from. */
    internal val contentPlayback: ContentPlayback? get() = owner.get()?.playback

    /** The ad's `VideoAds` tracks a player, now. */
    internal val isTrackingContent: Boolean get() = contentPlayback?.isTracking == true

    /** An overlay's duration in milliseconds of video, once shown over a tracked player (tests read it). */
    internal val contentClock: ContentTimeClock? get() = (contentRule as? OverlayRule)?.contentClock

    internal val isExpired: Boolean get() = Environment.now() - receivedAt >= payload.expiresMillis

    // region Loading and rejecting

    /**
     * Renders the creative off screen. Returns once it can be shown; throws [VideoAdException]
     * (`CreativeFailed`, `Invalidated`, `Destroyed`, `Disabled`). Idempotent: a second call waits for the first.
     * Callable from any dispatcher: the work runs on the main thread.
     */
    public suspend fun load(): Unit = withContext(Dispatchers.Main.immediate) { loadOnMain() }

    private suspend fun loadOnMain() {
        // Shown already: nothing to load, whatever the bid's lifetime says now.
        if (wasShown) return
        checkLoadable()
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
    public fun load(callback: VideoAdCallback) {
        Environment.scope.launch {
            // The app's own onAd may throw (a show that fails): that is not a failed load.
            try {
                load()
            } catch (error: VideoAdException) {
                callback.onError(error)
                return@launch
            }
            callback.onAd(this@VideoAd)
        }
    }

    /**
     * You won't use the ad; the server tells the advertiser why. Works until `show`, loaded or not. It never
     * throws: on an ad that was shown, has already ended (expired, rejected, its `VideoAds` destroyed, its
     * creative failed) or expires now, it does nothing and logs a line.
     */
    @MainThread
    @JvmOverloads
    public fun reject(reason: RejectReason = RejectReason.NotUsed) {
        IntegrationGuard.requireMainThread("VideoAd.reject()")
        withContentPlayhead {
            if (wasShown) {
                Logger.warning { "$format ad ${id.take(8)}: reject() after show does nothing; the ad was shown" }
                return@withContentPlayhead
            }
            invalidationReason?.let {
                Logger.info { "$format ad ${id.take(8)}: reject() does nothing, the ad already ended ($it)" }
                return@withContentPlayhead
            }
            if (isExpired) {
                // No notice: the server closes an expired ad itself.
                invalidate(InvalidationReason.Expired)
                Logger.info { "$format ad ${id.take(8)}: reject() does nothing, the ad expired" }
                return@withContentPlayhead
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
        SdkState.current.disabledReason?.let { throw VideoAdException.Disabled(it) }
        invalidationReason?.let { throw error(it) }
        if (isExpired) {
            invalidate(InvalidationReason.Expired)
            throw VideoAdException.Invalidated(InvalidationReason.Expired)
        }
    }

    /** [checkUsable] as `load` says it: a failed creative is `CreativeFailed`, however it was found. */
    @Suppress("SwallowedException") // the same failure, in the form `load` promises
    private fun checkLoadable() {
        try {
            checkUsable()
        } catch (error: VideoAdException.Invalidated) {
            val reason = error.reason
            if (reason is InvalidationReason.CreativeFailed) throw VideoAdException.CreativeFailed(reason.code)
            throw error
        }
    }

    private fun startLoading(): Deferred<Unit> {
        val owner = owner.get() ?: throw VideoAdException.Destroyed()
        val bridge = try {
            // A linear ad's duration is its video's length; an overlay's is its time on screen, not the video's.
            // A fact of the linear format, not of its screen.
            loadedBridge ?: makeBridge(
                checkNotNull(Environment.context) { "no application context" },
                payload.id,
                payload.frameUrl,
                payload.creative == AdPayload.Creative.VIDEO,
                payload.durationMillis.takeIf { format == AdFormat.LINEAR },
            ).also { bridge ->
                bridge.onEvent = { event -> handle(event) }
                loadedBridge = bridge
            }
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
                checkLoadable()
                throw error
            }
            // Expired while the page loaded, or the creative failed right after it reported ready: the result is dropped.
            checkLoadable()
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
            ErrorReport.Code.FRAME_LOAD_FAILED,
            ErrorReport.Stage.LOAD,
            sessionId = owner.get()?.sessionId,
            adId = id,
            vastCode = code,
            recovery = ErrorReport.Recovery.AD_DROPPED_BEFORE_SHOW,
        )
        invalidate(InvalidationReason.CreativeFailed(code))
        DebugReporter.event("ad.loadFailed", adId = id, level = DebugReporter.Level.WARNING, state = mapOf("code" to code))
        return VideoAdException.CreativeFailed(code)
    }

    // endregion

    // region Showing

    /**
     * Shows an overlay or pause ad inside [container], the view the content plays in. It either throws, and
     * nothing happened, or it was accepted: the ad is on screen, or, if your own code ended it during the call
     * (a hook of the container, the impression listener), `onAdEnded` already arrived (`hasEnded == true`). [insets] (pixels) keep it clear of your own controls, added to the system bars and cutout
     * that overlap the container; kept through resizes and rotations, counted in `ContainerTooSmall`'s
     * minimum; a full-screen pause ad ignores them: it covers the whole container, its page inside the
     * system bars and cutout. Throws, with nothing shown: `NotLoaded`, `AlreadyShown`, `AnotherAdShowing`,
     * `Invalidated` (`ContentChanged` too: show an episode's ads only in that episode, see `VideoAds.track`), `Destroyed`, `Disabled`, `CannotShowNow` (the container is not attached, not shown,
     * has no area, lies entirely outside its window such as a pager's neighbour page, its Activity is stopped or
     * the app is in the background, or it refuses a child: a ScrollView that has one, a ListView),
     * `ContainerTooSmall`, `ContentPaused` (an overlay over paused content; the ad stays ready in these three),
     * `WrongShowMethod` (a linear ad). The container must lay the ad over the content, as a `FrameLayout` does: in
     * a `LinearLayout` the ad goes after the player, gets no room and ends `INTERRUPTED` with no impression.
     */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun show(container: ViewGroup, insets: Insets = Insets.NONE) {
        IntegrationGuard.requireMainThread("VideoAd.show(container)")
        withContentPlayhead { showInline(container, insets) }
    }

    private fun showInline(container: ViewGroup, insets: Insets) {
        // The public guard: a fact of the linear format, not of its screen.
        if (format == AdFormat.LINEAR) {
            Environment.assertionFailure("VideoAd.show(container) on a linear ad; show(activity) shows it")
            throw VideoAdException.WrongShowMethod()
        }
        checkShowable()
        val screen = InlineScreen(this, Rect(maxOf(0, insets.left), maxOf(0, insets.top), maxOf(0, insets.right), maxOf(0, insets.bottom)))
        // 1. The container's checks and the variant, before anything changes: the ad stays ready if they fail.
        screen.prepare(container)
        // 2. Reserved before any of the app's code runs: adding the host calls the container's own hooks. A show
        //    from there finds the slot taken; a destroy there ends this ad. The container may refuse the host (a
        //    ScrollView with its child, a ListView) or its hook may throw: then nothing happened, see below.
        val bridge = shownBridge()
        val wasHeld = owner.get()?.ads?.get(format) === this
        reserve(screen)
        // 3. The host goes into the container.
        try {
            if (!screen.addHost(container, bridge)) return
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            unreserve(screen, wasHeld, error, container)
            // An ad its owner ended meanwhile (a destroy in the container's hook) has had its onAdEnded: show
            // was accepted and is over. Otherwise nothing was shown and nothing spent.
            if (hasEnded) return
            throw VideoAdException.CannotShowNow()
        }
        // 4. Still there after the container's hooks, it reached the screen.
        if (!screen.isStillOnScreen(container)) return
        contentRule.endIfContentStopped()
        reachedScreen()
    }

    /**
     * Shows a linear ad full screen, in the SDK's own Activity started from [activity]. Either it throws, and
     * nothing happened, or it was accepted and `onAdEnded` follows, at the latest once the screen is gone.
     * Throws as `show(container)` does; `CannotShowNow` for an Activity that is finishing, destroyed, stopped (not
     * visible) or in picture-in-picture (the ad stays ready; try again once it is visible). A started Activity
     * that is not resumed yet is fine: `onStart`, a result or permission callback, `onNewIntent`.
     */
    @MainThread
    @Throws(VideoAdException::class)
    public fun show(activity: Activity) {
        IntegrationGuard.requireMainThread("VideoAd.show(activity)")
        withContentPlayhead { showLinear(activity) }
    }

    private fun showLinear(activity: Activity) {
        // The public guard: a fact of the linear format, not of its screen.
        if (format != AdFormat.LINEAR) {
            Environment.assertionFailure("VideoAd.show(activity) on an overlay or pause ad; show(container) shows it")
            throw VideoAdException.WrongShowMethod()
        }
        checkShowable()
        // 1. The Activity must be able to start the screen, before anything changes: the ad stays ready otherwise.
        LinearScreen.checkCanStartFrom(activity, id)
        // 2. Reserved, then the screen starts, watched until it comes.
        val bridge = shownBridge()
        val screen = LinearScreen(this, bridge)
        reserve(screen)
        if (hasEnded) return
        screen.start(activity)
    }

    /**
     * Ends an overlay or pause ad because you say so; `onAdEnded(DISMISSED)` follows, synchronously. Before
     * `show` and after the end it does nothing. It never throws: on a linear ad, which ends by itself or by the
     * viewer (`VideoAds.destroy()` forces it off), it stops a debuggable build and does nothing in release.
     */
    @MainThread
    public fun dismiss() {
        IntegrationGuard.requireMainThread("VideoAd.dismiss()")
        // A fact of the linear format, not of its screen: it ends by itself or by the viewer.
        if (format == AdFormat.LINEAR) {
            Environment.assertionFailure("VideoAd.dismiss() on a linear ad; it ends by itself, VideoAds.destroy() forces it off")
            Logger.error { "ad ${id.take(8)}: dismiss() on a linear ad does nothing; it ends by itself or by the viewer, destroy() forces it off" }
            return
        }
        if (!wasShown || hasEnded) return
        Logger.debug { "ad ${id.take(8)}: dismissed by the app" }
        end(EndReason.DISMISSED)
    }

    override fun toString(): String =
        "VideoAd(id=${id.take(8)}, format=$format, price=$price, loaded=$isLoaded, shown=$wasShown, ended=$hasEnded)"

    private fun checkShowable() {
        // The app code a show runs before its checks: an ad on screen over content that no longer fits it ends
        // (its `onAdEnded`; another ad, see ContentRule.endIfContentStopped), and the player is read.
        if (!wasShown) ScreenSlot.current?.contentRule?.endIfContentStopped()
        val contentPaused = contentRule.refusesShow()
        if (wasShown) throw VideoAdException.AlreadyShown()
        checkUsable()
        checkEpisode()
        if (ScreenSlot.current != null) throw VideoAdException.AnotherAdShowing()
        if (!isLoaded) throw VideoAdException.NotLoaded()
        if (contentPaused) throw VideoAdException.ContentPaused()
    }

    /**
     * An episode's ads only in that episode: one requested for it is counted for it. An overlay or pause ad whose
     * episode no longer plays in the tracked player (another `VideoAds` took it, or it plays another item) ends
     * before show, with no beacon, and `show` throws. Not a linear ad (see ContentRule.checksEpisode).
     */
    private fun checkEpisode() {
        if (!contentRule.checksEpisode || owner.get()?.playback?.playsAnotherEpisode != true) return
        Logger.warning { "ad ${id.take(8)}: cannot show, its episode no longer plays in the tracked player; ended as ContentChanged" }
        invalidate(InvalidationReason.ContentChanged)
        throw VideoAdException.Invalidated(InvalidationReason.ContentChanged)
    }

    /** The page a show puts on screen: a loaded ad has one, made when its loading started. */
    private fun shownBridge(): FrameBridge = loadedBridge ?: throw VideoAdException.NotLoaded()

    /** Runs [work] with the content playhead read once before it (see [Tracker.withContentPlayhead]). */
    internal fun <T> withContentPlayhead(alsoFor: List<String> = emptyList(), work: () -> T): T = tracker.withContentPlayhead(alsoFor, work)

    private fun reserve(screen: AdScreen) {
        state.moveTo(Phase.Reserved)
        ScreenSlot.take(this, owner.get())
        this.screen = screen
    }

    /** The container refused the host: the reservation is undone, the host and the page released, the ad held again. */
    private fun unreserve(screen: InlineScreen, wasHeld: Boolean, error: Exception, container: ViewGroup) {
        Logger.warning { "ad ${id.take(8)}: the container refused the ad (${error.javaClass.simpleName}); not shown, the ad stays ready" }
        screen.releaseRefusedHost(container)
        if (!state.moveTo(Phase.Offered)) return
        if (this.screen != null) ScreenSlot.cancel(this, wasHeld)
        this.screen = null
    }

    /** The creative reached the screen: the page shows, the meter starts, and the beacons of `ad.show` fire. */
    internal fun reachedScreen(): Unit = withContentPlayhead {
        if (!state.moveTo(Phase.OnScreen)) return@withContentPlayhead
        loadedBridge?.show((screen as? InlineScreen)?.chosenSize)
        Logger.info { "ad ${id.take(8)}: shown" }
        DebugReporter.event("ad.shown", adId = id, state = mapOf("format" to format.wireName))
        val screen = this.screen
        startMeter()
        // Inline: the clock, the content rule and the holding Activity start (see InlineScreen.reachedScreen).
        screen?.reachedScreen()
        // At `ad.show`: creativeView and the AdChoices icon's view. The impression of an image or HTML creative
        // waits for the page's first layout with an area, a video's for its first frame (`video.start`). The
        // beacons use the playhead read before this block and call no app code, so one check suffices: what ran
        // above (the meter, following the content, which reads the player again, and the Activity) may have
        // ended the ad.
        if (hasEnded) return@withContentPlayhead
        tracker.fire("creativeView")
        tracker.fire("iconView")
        if (payload.creative != AdPayload.Creative.VIDEO) screen?.awaitImpression { withContentPlayhead { recordImpression() } }
    }

    // endregion

    // region The page's events

    // What the frame page reports, as beacons, the ad's state and the listener: VideoAd's half of the frame
    // protocol, the bridge (`FrameBridge`) being the other.

    /**
     * Every message of the page goes through here: [handleNow] with the content playhead read once first, when any URL
     * of the moment asks for it (`[CONTENTPLAYHEAD]`), so every beacon of one event carries the same time and the app's
     * player is asked once. The tracking URLs came from the ad server, and the tracker knows by itself whether one asks.
     * A click is the one event that brings URLs of its own, the click-through and its fallback, which the SDK fills in
     * too (`Tracker.clickThrough`): they are passed so a macro in them alone is found. `Open`'s URL is opened as it
     * is, with no macros, so it needs nothing.
     */
    internal fun handle(event: FrameEvent) {
        val urls = if (event is FrameEvent.Click) listOfNotNull(event.url, event.fallbackUrl) else emptyList()
        withContentPlayhead(urls) { handleNow(event) }
    }

    // One branch per event of the frame protocol.
    @Suppress("CyclomaticComplexMethod")
    private fun handleNow(event: FrameEvent) {
        if (hasEnded || invalidationReason != null) return
        when (event) {
            FrameEvent.Loaded -> {
                state.markLoaded()
                tracker.record(event)
                loadSignal?.complete(Unit)
                loadSignal = null
            }
            is FrameEvent.Error -> {
                tracker.record(event)
                reportCreativeError(event.code)
                val signal = loadSignal
                when {
                    signal != null -> {
                        loadSignal = null
                        Logger.warning { "ad ${id.take(8)}: creative failed to load, VAST error ${event.code}" }
                        signal.completeExceptionally(VideoAdException.CreativeFailed(event.code))
                    }
                    wasShown -> {
                        Logger.warning { "ad ${id.take(8)}: creative failed after show, VAST error ${event.code}" }
                        fail(VideoAdException.CreativeFailed(event.code))
                    }
                    // Died while waiting to be shown: the ad ends; `show` will throw.
                    else -> invalidate(InvalidationReason.CreativeFailed(event.code))
                }
            }
            is FrameEvent.Click -> click(event.url, event.fallbackUrl, event.area)
            is FrameEvent.Open -> {
                Logger.info { "ad ${id.take(8)}: open (${event.purpose}) ${event.url}" }
                tracker.record(event)
                Environment.context?.let { context ->
                    if (!LinkOpener.open(context, event.url, null)) Logger.warning { "ad ${id.take(8)}: nothing could open the click-through ${event.url}" }
                }
            }
            is FrameEvent.Close -> closeByPage(event.reason)
            is FrameEvent.Skip -> {
                tracker.record(event)
                end(EndReason.SKIPPED)
            }
            is FrameEvent.Video -> {
                when (event.event) {
                    FrameEvent.VideoEvent.START, FrameEvent.VideoEvent.RESUME -> state.markVideoPlaying(true)
                    FrameEvent.VideoEvent.PAUSE -> state.markVideoPlaying(false)
                    else -> Unit
                }
                meter?.countsTime = state.isVideoPlaying
                tracker.record(event)
                // The listener last: it may end the ad on the spot.
                if (event.event == FrameEvent.VideoEvent.START) recordImpression()
            }
            is FrameEvent.Progress -> tracker.record(event)
            is FrameEvent.Complete -> {
                state.markCompleted()
                state.markVideoPlaying(false)
                meter?.countsTime = state.isVideoPlaying
                tracker.record(event)
                Logger.info { "ad ${id.take(8)}: complete" }
            }
        }
    }

    /**
     * The page's own close control: how a page's close and skip end an ad, and why a linear ad's close can be a skip.
     *
     * ```
     *   what the viewer does (linear ad)        message        result
     *   taps Skip, after the skip offset        ad.skip        `skip` beacon, SKIPPED
     *   taps Close before the video finished    ad.close       `skip` beacon, SKIPPED
     *   closes the end card after the video     ad.close       `close` beacon, CLOSED
     *   nothing: the video ended, no end card   ad.close       COMPLETED, no `close` (nobody closed anything)
     * ```
     *
     * For a video ad what counts is whether the viewer watched it to the end. Leaving early is a skip, through
     * Skip or through Close alike, as video ad tracking counts it; `close` is for closing what already finished.
     * `isCompleted` is set only by the page's `complete` event, never by a close. Overlay and pause ads have no
     * "watch to the end": a close is CLOSED, or RESUMED when it resumed the content.
     */
    private fun closeByPage(reason: FrameEvent.CloseReason) {
        // Whether the ad is linear is a fact of the format, not of its screen.
        val outcome = closeOutcome(reason, isLinear = format == AdFormat.LINEAR, isCompleted = isCompleted)
        // The page ended itself because its creative ran to its end.
        if (outcome.completesAd) state.markCompleted()
        outcome.beacon?.let { tracker.fire(it) }
        end(outcome.endReason)
    }

    private fun reportCreativeError(vastCode: Int) {
        ErrorReporter.report(
            (loadedBridge?.failureCause ?: FrameBridge.FailureCause.PAGE).reportCode,
            if (wasShown) ErrorReport.Stage.PLAYBACK else ErrorReport.Stage.LOAD,
            sessionId = owner.get()?.sessionId,
            adId = id,
            vastCode = vastCode,
            underlying = loadedBridge?.failureUnderlying,
            recovery = if (wasShown) ErrorReport.Recovery.AD_ENDED_FAILED else ErrorReport.Recovery.AD_DROPPED_BEFORE_SHOW,
        )
    }

    // endregion

    // region Clicks

    /**
     * The page's `ad.click`, as Swift's `VideoAd+Click`: the `click` beacon, the click-through with the same fire
     * id (opened by [LinkOpener]), then `onAdClicked`.
     */
    internal fun click(url: String, fallback: String?, area: Click.Area) {
        Logger.info { "ad ${id.take(8)}: click ($area) $url" }
        // Every accepted click is counted: an end-card click on an ad without end-card entries (an overlay, a
        // pause ad) fires the main creative's.
        // One occurrence: the beacon and the click-through carry the same fire id.
        val isEndCard = area == Click.Area.EndCard && tracker.has("click", "endCard")
        val fireId = tracker.fire("click", part = if (isEndCard) "endCard" else null)
        val target = tracker.clickThrough(url, fireId)
        Environment.context?.let { context ->
            if (!LinkOpener.open(context, target, fallback?.let { tracker.clickThrough(it, fireId) })) {
                Logger.warning { "ad ${id.take(8)}: nothing could open the click-through $target" }
            }
        }
        // The listener hears nothing after `onAdEnded`. Opening the click-through does not end the ad today; the check
        // keeps that rule if it ever does.
        if (hasEnded) return
        listener?.onAdClicked(this, Click(area))
    }

    // endregion

    // region Measurement

    internal fun recordImpression() {
        if (hasEnded || !state.markImpression()) return
        tracker.fire("impression")
        Logger.info { "ad ${id.take(8)}: impression" }
        if (hasEnded) return
        listener?.onAdImpression(this)
    }

    private fun startMeter() {
        if (hasEnded || meter != null) return
        val webView = loadedBridge?.webView ?: return
        val meter = ViewabilityMeter(webView, if (payload.creative == AdPayload.Creative.VIDEO) 2_000 else 1_000)
        // MRC: two seconds of the video played. Exposure is measured from show, for the page.
        meter.countsTime = payload.creative != AdPayload.Creative.VIDEO || state.isVideoPlaying
        meter.onExposure = { percentage, visible ->
            val density = webView.resources.displayMetrics.density.toDouble()
            loadedBridge?.exposure(percentage, visible.left / density, visible.top / density, visible.width() / density, visible.height() / density)
        }
        meter.onVerdict = verdict@{ verdict ->
            // A verdict follows an impression: an ad that never had one (an untapped video, a video that failed
            // before its first frame) was never billed, and its DSP must not hear notViewable for it.
            if (!state.hasImpression) return@verdict
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
        // What depends on where the ad is: inline, the clock and the off-screen rule; linear, a screen over it.
        screen?.meter = meter
        screen?.configure()
        meter.start()
    }

    // endregion

    // region Ending

    internal fun fail(error: VideoAdException) {
        if (!wasShown || hasEnded) return
        state.markFailed(error)
        end(EndReason.FAILED)
    }

    internal fun end(reason: EndReason) {
        withContentPlayhead { endNow(reason) }
    }

    // Every end releases, in this order (an ad ended before show: [invalidate] releases what it ever had, the
    // load waiter, the page, the listener and its place in the owner):
    //  1. observers: the Activity listener (AdScreen.stopFollowing, from endNow);
    //  2. timers: the content rule's return check (ContentRule.cancel, from endNow);
    //  3. waiters: the first-layout wait (AdScreen.stopWaitingForImpression, from endNow);
    //  4. clock and meter: the clock and its onActivate, then the viewability meter (AdScreen.tearDown, from
    //     endNow);
    //  5. renderer: the host view (AdScreen.tearDown) and the page (hidden, released; endNow);
    //  6. the linear screen's callbacks: LinearAdActivity.forget (AdScreen.tearDown), the linear screen closed
    //     (AdScreen.close, from endNow), the screen dropped (finish);
    //  7. context: the WebView's context back to the application's (FrameBridge.useApplicationContext, from finish);
    //  8. the app-wide on-screen slot (ScreenSlot.release, from finish);
    //  9. player binding: the owner's showing ad and its ticks, VideoAds.adDidEnd (ScreenSlot.release);
    // 10. listener: dropped just before onAdEnded, the last call (finish).
    private fun endNow(reason: EndReason) {
        if (!state.moveTo(Phase.Ending(reason))) return
        screen?.stopFollowing()
        contentRule.cancel()
        screen?.stopWaitingForImpression()
        if (reason == EndReason.FAILED) state.failure?.let { listener?.onAdFailed(this, it) }
        val isInline = screen is InlineScreen
        // `[KONTEXT_VIEWDURATION]` is the active time, not the wall clock.
        val activeTime = screen?.tearDown() ?: 0
        val bridge = loadedBridge
        bridge?.hide()
        loadedBridge = null
        // A linear ad's screen still up closes, and `onAdEnded` waits for it to be gone (LinearScreen.close).
        val dismissing = screen?.close { finish(reason, bridge) } ?: false
        fireEndBeacons(reason, activeTime, isInline)
        Logger.info { "ad ${id.take(8)}: ended ($reason)" }
        DebugReporter.event("ad.ended", adId = id, state = mapOf("reason" to reason.name.lowercase()))
        if (!dismissing) finish(reason, bridge)
    }

    /** The beacons of an end, for an ad that was on screen: one that ended inside show, before it reached the screen, had no view. */
    private fun fireEndBeacons(reason: EndReason, activeTimeMillis: Long, isInline: Boolean) {
        if (!state.wasOnScreen) return
        // Shown, not billed: a video on screen that never reached its first frame (`video.start`) still gives the
        // server its outcome. Ended by the viewer, the app or an interruption: `close`. A failure has fired its
        // `error`; the page's own close or skip has fired its beacon.
        if (payload.creative == AdPayload.Creative.VIDEO && !state.hasImpression && reason in closedBeforeFirstFrame) tracker.fire("close")
        if (isInline) tracker.fire("overlayViewDuration", viewDurationMillis = activeTimeMillis)
    }

    /** [bridge]: the page the ad ended with, whose WebView goes back to the application's context. */
    private fun finish(reason: EndReason, bridge: FrameBridge?) {
        if (!state.moveTo(Phase.Ended(reason))) return
        // An ended ad keeps its facts, not its screen: an app that holds it holds no Activity or WebView.
        bridge?.useApplicationContext()
        if (screen != null) ScreenSlot.release(this)
        screen = null
        val listener = listener
        // The ad lets its listener go: an Activity held there must not outlive the ad.
        this.listener = null
        listener?.onAdEnded(this, reason)
    }

    // endregion

    // region Ending before show

    internal fun invalidate(reason: InvalidationReason) {
        if (!state.moveTo(Phase.Invalidated(reason))) return
        Logger.debug { "$format ad ${id.take(8)} ended before show: $reason" }
        // Wire names, as iOS sends them; a failed creative's code as its own key.
        val details = mapOf("reason" to reason.wireName) + ((reason as? InvalidationReason.CreativeFailed)?.let { mapOf("code" to it.code) } ?: emptyMap())
        DebugReporter.event("ad.invalidated", adId = id, state = details)
        loadedBridge?.hide()
        loadedBridge = null
        // Never shown, so no onAdEnded to let the listener go (see finish): it goes here.
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
        /** The ends a video before its first frame reports as `close`: none fired a beacon of its own. */
        private val closedBeforeFirstFrame = setOf(EndReason.DISMISSED, EndReason.DESTROYED, EndReason.INTERRUPTED, EndReason.COMPLETED)

        /**
         * How long a linear ad's screen may take to start, and the cap on its closing: a watchdog that reports
         * a screen the system never takes down. Tests shorten them.
         */
        var showTimeoutMillis = 5_000L
        var dismissalTimeoutMillis = 10_000L

        /**
         * How long after the app's return an overlay whose content paused in the background waits for the
         * content to play again before it ends. Tests shorten it.
         */
        var returnGraceMillis = 1_000L

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
        /** The name reports carry, the same on iOS: `expired`, `rejected`, `destroyed`, `contentChanged`, `creativeFailed`. */
        internal val wireName: String
            get() = when (this) {
                Expired -> "expired"
                Rejected -> "rejected"
                Destroyed -> "destroyed"
                ContentChanged -> "contentChanged"
                is CreativeFailed -> "creativeFailed"
            }

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

        /**
         * An overlay or pause ad, at `show`: its episode no longer plays in the tracked player. Another
         * `VideoAds` tracks that player now, or the player moved to another item than the one it played at
         * `track`. Shown, the ad would be counted for its own episode over another one.
         */
        public object ContentChanged : InvalidationReason() {
            override fun toString(): String = "ContentChanged"
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

            /**
             * A label of the page's own (`cta`, `logo`, ...), passed through unchanged.
             * @property label The page's label.
             */
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
         * The ad's screen disappeared for a reason the SDK did not cause: the container left the window or
         * became too small for the ad, another screen of the app covered the container's (its Activity stopped
         * while the app stays in front; the app going to the background only pauses the ad), the linear ad's
         * screen was destroyed under it or the Activity that started it finished. For an overlay with a
         * tracked player, also the content pausing (in the background: still paused a moment after the app's
         * return), ending or moving to another item; for a pause ad with a
         * tracked player, the content playing again (resumed outside the ad: the media notification, a headset).
         */
        INTERRUPTED,

        /** The app called `dismiss()`. */
        DISMISSED,

        /** The app called `destroy()` on the `VideoAds` while the ad was on screen. */
        DESTROYED,
    }
}

/**
 * What a page's `ad.close` does to an ad: the beacon it fires, if any, how the ad ends, and whether it completes it.
 * [reason] is the close reason the page gave: CLOSE (the viewer's close button), RESUME (a pause ad's Resume button) or
 * COMPLETE (the page ended itself after the video, with no end card). [isLinear]: a linear (video) ad. [isCompleted]:
 * the video already reached its end, which the SDK knows from the page's `complete` event, never from a close.
 *
 * The rows are checked top to bottom; the first that matches wins:
 *
 * ```
 *   #  when                          beacon  ends as       meaning
 *   1  linear and not completed      skip    SKIPPED       the viewer left a video ad before it finished: a skip for
 *      (any reason)                                        tracking, by the close button or anything else
 *   2  reason COMPLETE               none    COMPLETED     the ad ended by itself after its video ran out (it also
 *                                            (completes)   marks the ad completed); nobody closed it, so no close
 *   3  reason RESUME                 close   RESUMED       a pause ad's Resume button: closed to continue watching
 *   4  anything else (CLOSE)         close   CLOSED        closed with the close button: an overlay, a pause ad, or a
 *                                                          linear ad's end card after the video finished
 * ```
 *
 * Examples:
 * - A linear ad closed at second 10 of 30: row 1, `skip`, SKIPPED.
 * - A linear ad, video finished, the end card's close button: not row 1 (completed); reason CLOSE, row 4: `close`, CLOSED.
 * - A linear ad, video finished, no end card: the page sends COMPLETE, row 2: no beacon, COMPLETED.
 * - An overlay's close button: not linear, row 4: `close`, CLOSED.
 * - A pause ad's Resume: row 3: `close`, RESUMED.
 *
 * Row 1 comes first on purpose: a linear ad whose page says COMPLETE without the SDK having seen the video's
 * `complete` is a skip. The SDK trusts its own record of what was watched, which is what advertisers pay for.
 */
internal fun closeOutcome(reason: FrameEvent.CloseReason, isLinear: Boolean, isCompleted: Boolean): CloseOutcome = when {
    isLinear && !isCompleted -> CloseOutcome("skip", VideoAd.EndReason.SKIPPED)
    reason == FrameEvent.CloseReason.COMPLETE -> CloseOutcome(null, VideoAd.EndReason.COMPLETED, completesAd = true)
    reason == FrameEvent.CloseReason.RESUME -> CloseOutcome("close", VideoAd.EndReason.RESUMED)
    else -> CloseOutcome("close", VideoAd.EndReason.CLOSED)
}

/** A page's close as the ad carries it out: [beacon] fired, then the end with [endReason]. */
internal data class CloseOutcome(val beacon: String?, val endReason: VideoAd.EndReason, val completesAd: Boolean = false)
