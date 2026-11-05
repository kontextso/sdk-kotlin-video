package so.kontext.video.internal.screen

import android.app.Activity
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
import so.kontext.video.internal.platform.AppActivities
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.AdLifecycle

/**
 * A linear ad: the [LinearAdActivity] the SDK starts, which these [LinearAdActivity.Callbacks] fill with the
 * page, and its closing when the ad ends.
 */
internal class LinearScreen(ad: VideoAd, private val bridge: FrameBridge) : AdScreen(ad), LinearAdActivity.Callbacks {
    /** The screen once it attached, until it is gone or closing. */
    var activity: LinearAdActivity? = null
        private set

    /** What the ad runs once its closing screen is gone. */
    private var onGone: (() -> Unit)? = null

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
    override fun configure() {
        meter?.isCovered = { activity?.isCovered == true }
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

    override fun tearDown(): Long {
        meter?.stop()
        LinearAdActivity.forget(this)
        meter = null
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
        // 1. The screen finishes, marked as ended by the ad, with a fade.
        this.onGone = onGone
        screen.isEndingByAd = true
        screen.finish()
        @Suppress("DEPRECATION")
        screen.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        // 2. Its own signal, once it is gone, runs onGone (onScreenGone). 3. Or, past the cap, the timeout does.
        Environment.scope.launch {
            delay(VideoAd.dismissalTimeoutMillis)
            if (ad.state.phase !is AdLifecycle.Phase.Ended) reportDismissalTimeout(onGone)
        }
        return true
    }

    /** The closing screen was never taken down: reported, and [onGone] runs anyway (`onAdEnded` once). */
    private fun reportDismissalTimeout(onGone: () -> Unit) {
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
