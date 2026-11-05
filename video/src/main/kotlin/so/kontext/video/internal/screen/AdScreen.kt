package so.kontext.video.internal.screen

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.core.AdLifecycle
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.measurement.ActiveTimeClock
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.support.activity

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
 * show             attach(host): AdHostView into the   LinearAdActivity started; its attach (these
 *                  container                           Callbacks) puts the page in it
 * configure(meter) on-screen rule, seen → clock        covered by another Activity
 * reachedScreen()  the clock runs, the content rule    nothing: the screen's own onResume showed it
 *                  starts, the holding Activity
 * end:
 *   tearDown(meter) clock → meter → host view out;     meter stopped, the screen's registration
 *                  returns the active time             forgotten; returns 0
 *                  (overlayViewDuration)
 *   close(onGone)  nothing to wait for: false          the screen finishes; onGone once it is gone: true
 * ```
 *
 * A screen never ends the ad's life itself: it calls `ad.end(...)`, and `onGone` (the ad's `finish`) is the
 * only way it reaches `onAdEnded`.
 */
internal sealed class AdScreen {
    /** An overlay's or pause ad's view in the app's container, not a screen of its own. */
    abstract val isInline: Boolean

    /** The view whose first layout with an area counts an image or HTML creative's impression. */
    abstract val impressionTarget: View?

    /** The creative was placed in a frame with an area: the impression may count. */
    abstract val isPlaced: Boolean

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

    /**
     * An overlay or pause ad: the [AdHostView] in the app's container, and the [ActiveTimeClock] that counts its
     * time seen (and an overlay's wall-clock duration). It follows the Activity holding the container.
     */
    class InlineScreen(private val ad: VideoAd) : AdScreen() {
        /** The view the SDK put into the app's container; gone once the ad ends, or the container refused it. */
        var hostView: AdHostView? = null

        /** Time seen on screen; it also ends an overlay whose wall-clock duration elapsed. From [reachedScreen] to [tearDown]. */
        var clock: ActiveTimeClock? = null
            private set

        override val isInline: Boolean get() = true

        // The host: it has an area only where the container gives the ad room, and places the page.
        override val impressionTarget: View? get() = hostView
        override val isPlaced: Boolean get() = hostView?.hasPlacedContent ?: true

        /** `show` made [host] for the container: the screen hears its fit and its window. */
        fun attach(host: AdHostView) {
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
            host.onWindowVisibility = { visible -> clock?.windowVisibilityChanged(visible) }
        }

        /**
         * The meter is the one answer to "is it seen": the clock counts only then, and an ad nobody can see while
         * the app is in front (a feed cell swiped away) ends and frees the slot.
         */
        override fun configure(meter: ViewabilityMeter) {
            meter.onSeen = { seen -> clock?.seenChanged(seen) }
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
            val clock = ActiveTimeClock(ad.contentRule.wallClockDurationMillis, ad.id) {
                if (ad.hasEnded) return@ActiveTimeClock
                Logger.debug { "ad ${ad.id.take(8)}: overlay duration of ${ad.durationMillis ?: 0} ms on screen elapsed" }
                ad.state.markCompleted()
                ad.end(EndReason.COMPLETED)
            }
            this.clock = clock
            clock.start(isVisible = hostView?.windowVisibility == View.VISIBLE, isSeen = ad.meter?.isSeen ?: true)
            ad.contentRule.started(clock)
            hostView?.activity?.let(ad::followHoldingActivity)
        }

        override fun tearDown(meter: ViewabilityMeter?): Long {
            val activeTime = clock?.stop() ?: 0
            // The stopped clock's callback reaches the owner's playback: an ended ad an app keeps must not keep its VideoAds.
            clock?.onActivate = null
            clock = null
            meter?.stop()
            hostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            hostView = null
            return activeTime
        }

        override fun close(onGone: () -> Unit): Boolean = false
    }

    /**
     * A linear ad: the [LinearAdActivity] the SDK starts, which these [LinearAdActivity.Callbacks] fill with the
     * page, and its closing when the ad ends.
     */
    class LinearScreen(private val ad: VideoAd, private val bridge: FrameBridge) : AdScreen(), LinearAdActivity.Callbacks {
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
            ad.contextWrapper.baseContext = activity
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
                        ErrorReporter.Code.DISMISSAL_TIMED_OUT,
                        ErrorReporter.Stage.PRESENTATION,
                        sessionId = ad.owner.get()?.sessionId,
                        adId = ad.id,
                        recovery = ErrorReporter.Recovery.DID_END_DELIVERED,
                    )
                    onGone()
                }
            }
            return true
        }
    }
}
