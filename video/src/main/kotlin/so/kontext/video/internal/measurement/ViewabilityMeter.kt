package so.kontext.video.internal.measurement

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.VisibleForTesting
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.isLaidOutInWindow
import java.lang.ref.WeakReference

/**
 * A measuring loop: every 250 ms ([intervalMillis]) it asks how much of the ad's view the viewer can see right
 * now, and turns the answer into a few signals. It decides the one viewability event, the industry's (MRC)
 * rule: `viewable` once at least 50 % of the ad was on screen without a break for 1 s (2 s for a video),
 * `notViewable` when the ad ended before that, `viewUndetermined` when it could not measure.
 *
 * ```
 * every 250 ms: tick()
 *   ├─ measure:   ViewExposure.measure(view): share on screen (0–1) and the visible rectangle
 *   ├─ report:    changed? onExposure(share, rect)       → the page, which tells the creative
 *   │             any of it seen? changed: onSeen(bool)  → OnScreenTimeClock
 *   ├─ off-screen: nothing seen for 1 s while the app is in front: onOffScreen  → the ad ends
 *   └─ dwell:     ≥ 50 % without a break for the required time: onVerdict(VIEWABLE), once
 * stop() at the ad's end: no verdict yet? NOT_VIEWABLE (VIEW_UNDETERMINED when the view is gone)
 * ```
 *
 * Outputs: [onVerdict] (the beacon), [onExposure] (`ad.exposure` to the page), [onSeen] / [isSeen] (the clock),
 * [onOffScreen] and [onDetached] (the ad ends). Inputs: [isCovered] (something it cannot measure covers the ad,
 * such as another screen over a linear ad), [isActive] (the app is in front), [countsTime] (off while a video
 * creative is not playing: a paused video does not count towards its 2 s).
 *
 * The two hard parts. The geometry is [ViewExposure]'s (and [Polygon]'s). The timing, [countDwell]:
 * `exposedSince` marks when the ad reached 50 %, and starts again
 * when the share drops, the video pauses, or the loop stalled (a frozen app cannot prove the time was
 * continuous).
 *
 * Safety: nothing is reported before the first layout ([isMeasured]), where every measurement is 0 only for want
 * of a size and a page would pause its video for nothing; one tick of grace before [onDetached], since a view
 * moved to another parent in the same moment is not gone; the verdict is decided once.
 *
 * Not OnScreenTimeClock: that one adds up any time seen, with breaks; this one judges the 50 % rule.
 */
internal class ViewabilityMeter(view: View, private val requiredMillis: Long) {
    enum class Verdict(val event: String) { VIEWABLE("viewable"), NOT_VIEWABLE("notViewable"), VIEW_UNDETERMINED("viewUndetermined") }

    /**
     * Exposure changed: share on screen (0–1) and the visible rectangle in the view's own pixels. From the first
     * measurement of the view laid out in a window on; a 0 after that (covered, detached) is reported.
     */
    var onExposure: ((Double, Rect) -> Unit)? = null
    var onVerdict: ((Verdict) -> Unit)? = null

    /** The view left the window and did not come back within one interval; on every tick while it stays out, from the second. */
    var onDetached: (() -> Unit)? = null

    /**
     * Whether something the meter cannot see covers the view while it stays in its window, visible: another
     * Activity over a linear ad's screen, translucent or sheet-like. Asked on every tick; covered reads as 0.
     */
    var isCovered: (() -> Boolean)? = null

    /**
     * Whether any of the ad can be seen, from the first measurement on (true before it): the one answer to "is
     * it seen" the clock and the off-screen rule follow. [onSeen] hears every change.
     */
    var isSeen = true
        private set
    var onSeen: ((Boolean) -> Unit)? = null

    /** Whether the ad's window is in front: the off-screen rule counts only then. */
    var isActive: () -> Boolean = { true }

    /**
     * Nothing of the ad was seen for [offScreenGraceMillis] while [isActive], after the first measurement: a view
     * scrolled or paged away while it stays in its window. On every tick from then.
     */
    var onOffScreen: (() -> Unit)? = null
    private var unseenSince: Long? = null

    var verdict: Verdict? = null
        private set

    /** Whether exposure counts towards the dwell now: off while a video creative is not playing. Off restarts the dwell. */
    var countsTime = true
        set(value) {
            field = value
            if (!value) exposedSince = null
        }

    private val view = WeakReference(view)
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            if (isRunning) handler.postDelayed(this, intervalMillis)
        }
    }
    private var isRunning = false
    private var isStopped = false
    private var exposedSince: Long? = null
    private var lastTick: Long? = null
    private var lastPercentage = -1.0
    private var lastVisible = Rect()
    private var detachedTicks = 0

    /**
     * The view has been laid out in a window with an area once. Before that every measurement is 0 for want
     * of a layout, not because the viewer cannot see the ad, and a page that pauses its video on exposure 0
     * would pause and resume at once: nothing is reported until then.
     */
    private var isMeasured = false

    fun start() {
        if (isRunning || isStopped) return
        isRunning = true
        handler.postDelayed(tickRunnable, intervalMillis)
        tick()
    }

    /** The ad ended: a verdict is still owed when none was reached. */
    fun stop() {
        isStopped = true
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        if (verdict == null) decide(if (view.get() == null) Verdict.VIEW_UNDETERMINED else Verdict.NOT_VIEWABLE)
    }

    /** One measurement. Internal so tests can drive it with a frozen clock. */
    fun tick() {
        // 1. Did the loop stall (more than two ticks, and at least 500 ms, since the last: the app froze or slept)?
        //    Then the 50 % stretch cannot be called continuous, and countDwell starts it again.
        val now = Environment.now()
        val isStalled = lastTick?.let { now - it > maxOf(intervalMillis * 2, SHORTEST_STALL_MILLIS) } ?: false
        lastTick = now
        // 2. Is the view still there, in a window? Held weakly: the meter never keeps a view alive.
        val view = view.get()
        if (view == null || !view.isAttachedToWindow) {
            report(0.0, Rect())
            exposedSince = null
            // One tick of grace: a view re-parented within the same turn is not gone. Two in a row: the ad ends.
            detachedTicks += 1
            if (view != null && detachedTicks >= 2) onDetached?.invoke()
            return
        }
        detachedTicks = 0
        // 3. Measure: the share on screen and its rectangle (0 when something covers it).
        val (percentage, visible) = seen(view)
        // 4. Tell: onExposure when it changed, onSeen when "any of it visible" flipped. First, so the page and the
        //    clock are current before the rules below may end the ad.
        report(percentage, visible)
        // A listener may have ended the ad just now (each check below can too).
        if (isStopped) return
        // 5. Nothing seen for a second while the app is in front: onOffScreen, the ad ends.
        checkOffScreen(now)
        // 6. The 50 % rule: the continuous time at 50 % or more; long enough, the verdict VIEWABLE, once.
        if (!isStopped) countDwell(percentage, now, isStalled)
    }

    /** The MRC rule's continuous seconds at 50 % or more; a gap between ticks starts them again. */
    private fun countDwell(percentage: Double, now: Long, isStalled: Boolean) {
        if (percentage >= THRESHOLD && countsTime) {
            if (exposedSince == null || isStalled) exposedSince = now
            exposedSince?.let { if (now - it >= requiredMillis) decide(Verdict.VIEWABLE) }
        } else {
            exposedSince = null
        }
    }

    /** The view's exposure, or none while something covers it. */
    private fun seen(view: View): Exposure = if (isCovered?.invoke() == true) Exposure.NONE else ViewExposure.measure(view)

    private fun checkOffScreen(now: Long) {
        if (!isMeasured || isSeen || !isActive()) {
            unseenSince = null
            return
        }
        val since = unseenSince ?: now.also { unseenSince = it }
        if (now - since >= offScreenGraceMillis) onOffScreen?.invoke()
    }

    private fun report(percentage: Double, visible: Rect) {
        if (!isMeasured) {
            val view = view.get() ?: return
            if (!view.isLaidOutInWindow) return
            isMeasured = true
        }
        val isSeenNow = percentage > 0
        if (isSeenNow != isSeen) {
            isSeen = isSeenNow
            onSeen?.invoke(isSeenNow)
        }
        val rounded = Math.round(percentage * 100) / 100.0
        if (rounded == lastPercentage && visible == lastVisible) return
        lastPercentage = rounded
        lastVisible = Rect(visible)
        onExposure?.invoke(rounded, visible)
    }

    private fun decide(verdict: Verdict) {
        if (this.verdict != null) return
        this.verdict = verdict
        onVerdict?.invoke(verdict)
    }

    companion object {
        /**
         * How often the box is measured. A constant in spirit: only tests set it, shorter to run fast, or so long
         * that the test calls [tick] itself.
         */
        @set:VisibleForTesting
        var intervalMillis = 250L

        /** How long an ad may stay unseen in front before it ends. A constant in spirit: only tests set it. */
        @set:VisibleForTesting
        var offScreenGraceMillis = 1_000L

        /**
         * A gap between two ticks longer than this, and than two intervals, means the loop stalled (the app froze
         * or slept): time nobody measured cannot count as continuous, and the 50 % stretch starts again.
         */
        const val SHORTEST_STALL_MILLIS = 500L

        /** The MRC rule's share: at least half of the ad on screen. */
        const val THRESHOLD = 0.5
    }
}
