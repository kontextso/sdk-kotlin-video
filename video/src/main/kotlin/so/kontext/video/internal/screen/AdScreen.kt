package so.kontext.video.internal.screen

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.AppActivities
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.AdLifecycle
import java.lang.ref.WeakReference

/**
 * Where an ad on screen is shown: the one place where the formats differ in how they are put on screen,
 * measured and taken off.
 *
 * Why: an overlay or pause ad sits inline, in a view the SDK adds to the app's container; a linear ad has a
 * full-screen Activity of its own. Kept in one class per kind of screen, `VideoAd` and `ShownAd` ask
 * "the screen" and never the format.
 *
 * How: `show` makes the screen as it reserves the slot and hands it to the ad's `ShownAd`; the ad then calls
 * it at fixed moments:
 *
 * ```
 *                  InlineScreen                        LinearScreen
 * show             attach(host): InlineAdView into the LinearAdActivity started; its attach (these
 *                  container                           Callbacks) puts the page in it; the
 *                                                      Activity that started it followed
 * configure(meter) on-screen rule, seen → clock        covered by another Activity
 * reachedScreen()  the clock runs, the content rule    nothing: the screen's own onResume showed it
 *                  starts, the holding Activity
 *                  followed
 * end:
 *   stopFollowing() the Activity's listener removed    the same
 *   tearDown(meter) clock → meter → host view out;     meter stopped, the screen's registration
 *                  returns the active time             forgotten; returns 0
 *                  (overlayViewDuration)
 *   close(onGone)  nothing to wait for: false          the screen finishes; onGone once it is gone: true
 * ```
 *
 * A screen never ends the ad's life itself: it calls `ad.end(...)`, and `onGone` (the ad's `finish`) is the
 * only way it reaches `onAdEnded`.
 */
internal sealed class AdScreen(
    /** The ad this screen shows. */
    val ad: VideoAd,
) {
    /** An overlay's or pause ad's view in the app's container, not a screen of its own. */
    abstract val isInline: Boolean

    /** The view whose first layout with an area counts an image or HTML creative's impression. */
    abstract val impressionTarget: View?

    /** The creative was placed in a frame with an area: the impression may count. */
    abstract val isPlaced: Boolean

    /**
     * Stops waiting for the page's first layout, where an image or HTML creative's impression counts. Inline in
     * practice (a linear ad's creative is a video, whose impression is its first frame); kept on every screen so
     * a linear ad with another creative waits the same way.
     */
    var impressionWait: (() -> Unit)? = null

    /** Follows the Activity the screen depends on while the ad is on screen; removed when the ad ends. */
    private var activityListener: ((Activity, AppActivities.Event) -> Unit)? = null

    /** The meter's inputs and outputs that depend on where the ad is. */
    abstract fun configure(meter: ViewabilityMeter)

    /** The ad reached the screen: what runs while it is on screen starts. */
    abstract fun reachedScreen()

    /**
     * The ad is ending, in this order: its clock freezes for good, [meter] stops, what it put on screen goes.
     * Returns the active time seen on screen.
     */
    abstract fun tearDown(meter: ViewabilityMeter?): Long

    /**
     * Closes what is still up. Returns `true` when the ad must wait: [onGone] then runs once the screen is gone
     * (or after a timeout); `false` when nothing is left to wait for, and [onGone] never runs.
     */
    abstract fun close(onGone: () -> Unit): Boolean

    /** The ad is ending: the Activity it followed is no longer heard. */
    fun stopFollowing() {
        activityListener?.let(AppActivities::removeListener)
        activityListener = null
    }

    /** The ad is ending: the first-layout wait stops, its impression never counted. */
    fun stopWaitingForImpression() {
        impressionWait?.invoke()
        impressionWait = null
    }

    /** Hears [activity]'s events until the ad ends. */
    protected fun follow(activity: Activity, onEvent: (Activity, AppActivities.Event) -> Unit) {
        val target = WeakReference(activity)
        val listener: (Activity, AppActivities.Event) -> Unit = { which, event -> if (which === target.get() && !ad.hasEnded) onEvent(which, event) }
        activityListener?.let(AppActivities::removeListener)
        activityListener = listener
        AppActivities.addListener(listener)
    }

    /**
     * An overlay or pause ad: the [InlineAdView] in the app's container, and the [OnScreenTimeClock] that counts its
     * time seen (and an overlay's wall-clock duration). It follows the Activity holding the container.
     */
    class InlineScreen(
        ad: VideoAd,
        /** The app's own insets from `show(container, insets)`, in pixels, never negative; kept through resizes. */
        val extraInsets: Rect,
    ) : AdScreen(ad) {
        /** The view the SDK put into the app's container; gone once the ad ends, or the container refused it. */
        var hostView: InlineAdView? = null

        /** The variant `chooseSize` picked from the ad's sizes at show (dp); `null` for a full-screen pause ad. */
        var chosenSize: AdPayload.Size? = null

        /** The Activity holding the container, while the ad is on screen. */
        var holdingActivity: WeakReference<Activity>? = null
            private set

        /** Time seen on screen; it also ends an overlay whose wall-clock duration elapsed. From [reachedScreen] to [tearDown]. */
        var onScreenClock: OnScreenTimeClock? = null
            private set

        override val isInline: Boolean get() = true

        // The host: it has an area only where the container gives the ad room, and places the page.
        override val impressionTarget: View? get() = hostView
        override val isPlaced: Boolean get() = hostView?.hasPlacedContent ?: true

        /** `show` made [host] for the container: the screen hears its fit and its window. */
        fun attach(host: InlineAdView) {
            hostView = host
            host.onUnfit = {
                if (!ad.hasEnded) {
                    Logger.warning {
                        "ad ${ad.id.take(8)}: the container became too small for the ad, or gives it no room (not a FrameLayout-like container); " +
                            "ended as interrupted"
                    }
                    ad.end(EndReason.INTERRUPTED)
                }
            }
            host.onWindowVisibility = { visible -> onScreenClock?.windowVisibilityChanged(visible) }
        }

        /**
         * The meter is the one answer to "is it seen": the clock counts only then, and an ad nobody can see while
         * the app is in front (a feed cell swiped away) ends and frees the slot.
         */
        override fun configure(meter: ViewabilityMeter) {
            meter.onSeen = { seen -> onScreenClock?.seenChanged(seen) }
            meter.isActive = { Environment.isAppInForeground() && hostView?.windowVisibility == View.VISIBLE }
            meter.onOffScreen = {
                if (!ad.hasEnded) {
                    Logger.warning { "ad ${ad.id.take(8)}: nothing of it seen for ${ViewabilityMeter.offScreenGraceMillis} ms; ended as interrupted" }
                    ad.end(EndReason.INTERRUPTED)
                }
            }
        }

        /**
         * 1. The clock starts, with the content rule's wall-clock duration (an overlay with no tracked player).
         * 2. The content rule starts (an overlay counts video time from the playhead now).
         * 3. The ad follows the Activity holding the container.
         */
        override fun reachedScreen() {
            val clock = OnScreenTimeClock(ad.contentRule.wallClockDurationMillis, ad.id) {
                if (ad.hasEnded) return@OnScreenTimeClock
                Logger.debug { "ad ${ad.id.take(8)}: overlay duration of ${ad.durationMillis ?: 0} ms on screen elapsed" }
                ad.state.markCompleted()
                ad.end(EndReason.COMPLETED)
            }
            this.onScreenClock = clock
            clock.start(isVisible = hostView?.windowVisibility == View.VISIBLE, isSeen = ad.meter?.isSeen ?: true)
            ad.contentRule.started(clock)
            hostView?.activity?.let(::followHoldingActivity)
        }

        override fun tearDown(meter: ViewabilityMeter?): Long {
            val activeTime = onScreenClock?.stop() ?: 0
            // The stopped clock's callback reaches the owner's playback: an ended ad an app keeps must not keep its VideoAds.
            onScreenClock?.onActivate = null
            onScreenClock = null
            meter?.stop()
            hostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            hostView = null
            return activeTime
        }

        override fun close(onGone: () -> Unit): Boolean = false

        /**
         * Another Activity of the app covers the one holding the container (it stopped while the app stays in
         * front): the ad ends, interrupted, as on iOS, where a full-screen cover takes the presenter's view out of
         * its window. The app going to the background only freezes the ad's clock (and an overlay's content
         * pausing there, see `OverlayRule`); a stop for a configuration change recreates the Activity, and the
         * container leaving the window ends the ad there.
         */
        private fun followHoldingActivity(holder: Activity) {
            holdingActivity = WeakReference(holder)
            follow(holder) { activity, event ->
                if (event == AppActivities.Event.RESUMED) ad.contentRule.holdingActivityResumed()
                if (event == AppActivities.Event.STOPPED && !activity.isChangingConfigurations && Environment.isAppInForeground()) {
                    Logger.warning { "ad ${ad.id.take(8)}: another screen of the app covers the ad's; ended as interrupted" }
                    ad.end(EndReason.INTERRUPTED)
                }
            }
        }
    }

    /**
     * A linear ad: the [LinearAdActivity] the SDK starts, which these [LinearAdActivity.Callbacks] fill with the
     * page, and its closing when the ad ends.
     */
    class LinearScreen(ad: VideoAd, private val bridge: FrameBridge) : AdScreen(ad), LinearAdActivity.Callbacks {
        /** The screen once it attached, until it is gone or closing. */
        var activity: LinearAdActivity? = null
            private set

        /** What the ad runs once its closing screen is gone. */
        private var onGone: (() -> Unit)? = null

        override val isInline: Boolean get() = false
        override val impressionTarget: View? get() = ad.loadedBridge?.webView
        override val isPlaced: Boolean get() = true

        override fun attach(activity: LinearAdActivity, root: FrameLayout) {
            if (ad.hasEnded) {
                activity.isEndingByAd = true
                activity.finish()
                return
            }
            this.activity = activity
            bridge.useShowingContext(activity)
            (bridge.webView.parent as? ViewGroup)?.removeView(bridge.webView)
            root.addView(bridge.webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onScreenShown() = ad.reachedScreen()

        override fun onScreenGone(byAd: Boolean) {
            activity = null
            if (byAd) {
                onGone?.invoke()
            } else if (!ad.hasEnded) {
                Logger.warning { "ad ${ad.id.take(8)}: the linear ad's screen was taken away; ended as interrupted" }
                ad.end(EndReason.INTERRUPTED)
            }
        }

        // Another Activity over the screen leaves its view in a visible window, measured whole there. No
        // off-screen rule: a sheet over it after a click is the viewer's own doing.
        override fun configure(meter: ViewabilityMeter) {
            meter.isCovered = { activity?.isCovered == true }
        }

        override fun reachedScreen() = Unit

        /**
         * The Activity that started a linear ad finishes under it (the app closed that screen): the ad ends,
         * interrupted, and its screen closes, as on iOS when the presenter is dismissed. Not a destroy without
         * finishing (a configuration change, or the system reclaiming a screen behind): the app comes back to it
         * when the ad ends.
         */
        fun followStartingActivity(starter: Activity) = follow(starter) { activity, event ->
            if (event == AppActivities.Event.DESTROYED && activity.isFinishing) {
                Logger.warning { "ad ${ad.id.take(8)}: the Activity that started the linear ad finished; ended as interrupted" }
                ad.end(EndReason.INTERRUPTED)
            }
        }

        override fun tearDown(meter: ViewabilityMeter?): Long {
            meter?.stop()
            LinearAdActivity.forget(this)
            return 0
        }

        /**
         * Every way the ad ends closes a screen still up, an interruption too: its page is gone and Back does
         * nothing there. A screen the system took away has already cleared [activity].
         *
         * 1. The screen finishes, marked as ended by the ad, with a fade.
         * 2. `onAdEnded` waits for it to be gone, so the app can start its next screen from it: its own signal
         *    (its stop as it finishes, or its destroy) runs [onGone].
         * 3. A screen the system never takes down is a fault: after a cap well past any transition it is
         *    reported, and [onGone] runs anyway (the ad delivers `onAdEnded` once).
         */
        override fun close(onGone: () -> Unit): Boolean {
            val screen = activity
            activity = null
            if (screen == null || screen.isFinishing || screen.isDestroyed) return false
            this.onGone = onGone
            screen.isEndingByAd = true
            screen.finish()
            @Suppress("DEPRECATION")
            screen.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            Environment.scope.launch {
                delay(VideoAd.dismissalTimeoutMillis)
                if (ad.state.phase !is AdLifecycle.Phase.Ended) {
                    Logger.error { "ad ${ad.id.take(8)}: the linear ad's screen did not close in ${VideoAd.dismissalTimeoutMillis / 1000} s; onAdEnded delivered anyway" }
                    DebugReporter.event("ad.dismissalTimedOut", adId = ad.id, level = DebugReporter.Level.WARNING)
                    ErrorReporter.report(
                        ErrorReport.Code.DISMISSAL_TIMED_OUT,
                        ErrorReport.Stage.PRESENTATION,
                        sessionId = ad.owner.get()?.sessionId,
                        adId = ad.id,
                        recovery = ErrorReport.Recovery.DID_END_DELIVERED,
                    )
                    onGone()
                }
            }
            return true
        }
    }
}
