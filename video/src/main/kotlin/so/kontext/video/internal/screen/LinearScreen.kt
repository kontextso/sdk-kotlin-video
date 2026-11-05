package so.kontext.video.internal.screen

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAdException
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
    /** The ad's Activity once it attached, until it is gone or closing. */
    var activity: LinearAdActivity? = null
        private set

    /** What the ad runs once its closing Activity is gone. */
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

    // Another Activity over the ad's leaves its view in a visible window, measured whole there. No
    // off-screen rule: a sheet over it after a click is the viewer's own doing.
    override fun configure() {
        meter?.isCovered = { activity?.isCovered == true }
    }

    override fun reachedScreen() = Unit

    /**
     * The Activity that started a linear ad finishes under it (the app closed it): the ad ends,
     * interrupted, and its own Activity closes, as on iOS when the presenter is dismissed. Not a destroy without
     * finishing (a configuration change, or the system reclaiming an Activity behind): the app comes back to it
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
     * Every way the ad ends closes its Activity still up, an interruption too: its page is gone and Back does
     * nothing there. An Activity the system took away has already cleared [activity].
     *
     * 1. The Activity finishes, marked as ended by the ad, with a fade.
     * 2. `onAdEnded` waits for it to be gone, so the app can start its next Activity from it: its own signal
     *    (its stop as it finishes, or its destroy) runs [onGone].
     * 3. An Activity the system never takes down is a fault: after a cap well past any transition it is
     *    reported, and [onGone] runs anyway (the ad delivers `onAdEnded` once).
     */
    override fun close(onGone: () -> Unit): Boolean {
        val screen = activity
        activity = null
        if (screen == null || screen.isFinishing || screen.isDestroyed) return false
        // 1. The Activity finishes, marked as ended by the ad, with a fade.
        this.onGone = onGone
        screen.isEndingByAd = true
        screen.finish()
        @Suppress("DEPRECATION")
        screen.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        // 2. Its own signal, once it is gone, runs onGone (onScreenGone). 3. Or, past the cap, the timeout does.
        Environment.scope.launch {
            delay(dismissalTimeoutMillis)
            if (ad.state.phase !is AdLifecycle.Phase.Ended) reportDismissalTimeout(onGone)
        }
        return true
    }

    /** The closing Activity was never taken down: reported, and [onGone] runs anyway (`onAdEnded` once). */
    private fun reportDismissalTimeout(onGone: () -> Unit) {
        Logger.error { "ad ${ad.id.take(8)}: the linear ad's screen did not close in ${dismissalTimeoutMillis / 1000} s; onAdEnded delivered anyway" }
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

    /**
     * Starts the ad's Activity from [starter], which it follows from now on. Any launch that throws (the Activity
     * stripped from the merged manifest, a refused start, an app's own startActivity override or a system state
     * that throws something else) is the same failure. An Activity that never starts (the system refused it, or the
     * launch threw) must not hold the slot forever: a watchdog ends the ad, failed, once [showTimeoutMillis]
     * passed with none in front. A launch that threw takes the same path at once, after show returns, as
     * Swift's refused show does: show was accepted, so the ad ends with onAdFailed and onAdEnded(FAILED). On the
     * SDK's own scope when the owner is gone, so the slot is freed whatever holds the ad.
     */
    fun start(starter: Activity) {
        followStartingActivity(starter)
        val started = try {
            LinearAdActivity.start(starter, this)
            true
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            Logger.error { "ad ${ad.id.take(8)}: the linear ad's screen could not start (${error.javaClass.simpleName})" }
            false
        }
        (ad.owner.get()?.scope ?: Environment.scope).launch {
            if (started) delay(showTimeoutMillis) else yield()
            // On screen, not merely created: a screen that attached but never came to the front (started, never
            // resumed) would otherwise hold the slot for good.
            if (ad.hasEnded || ad.state.wasOnScreen) return@launch
            val what = if (activity == null) "did not start" else "never came to the front"
            Logger.error { "ad ${ad.id.take(8)}: the linear ad's screen $what; ended as failed" }
            LinearAdActivity.forget(this@LinearScreen)
            ErrorReporter.report(
                ErrorReport.Code.PRESENTATION_FAILED,
                ErrorReport.Stage.PRESENTATION,
                sessionId = ad.owner.get()?.sessionId,
                adId = ad.id,
                recovery = ErrorReport.Recovery.AD_ENDED_FAILED,
            )
            ad.fail(VideoAdException.CannotShowNow())
        }
    }

    companion object {
        /**
         * How long a linear ad's Activity may take to start, and the cap on its closing: a watchdog that reports
         * an Activity the system never takes down. Tests shorten them.
         */
        var showTimeoutMillis = 5_000L
        var dismissalTimeoutMillis = 10_000L

        /**
         * Throws `CannotShowNow`, nothing changed, when [activity] cannot start the ad's Activity: it is finishing or
         * destroyed, or not visible (stopped, or in picture-in-picture). The system would not start it
         * from there (or not where the viewer sees it), and the bid would be spent on an Activity that never comes.
         */
        fun checkCanStartFrom(activity: Activity, adId: String) {
            if (activity.isFinishing || activity.isDestroyed) {
                Logger.warning { "ad ${adId.take(8)}: cannot show, the Activity is finishing" }
                throw VideoAdException.CannotShowNow()
            }
            if (!AppActivities.isVisibleToViewer(activity)) {
                Logger.warning { "ad ${adId.take(8)}: cannot show, the Activity is not visible (stopped, or in picture-in-picture)" }
                throw VideoAdException.CannotShowNow()
            }
        }
    }
}
