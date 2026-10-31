package so.kontext.video.internal.rendering

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import so.kontext.video.internal.support.Environment
import java.lang.ref.WeakReference

/**
 * Measures how much of an ad's view is on screen while it is shown, and decides the one viewability event:
 * `viewable` once at least 50 % of the box was on screen for the required continuous time (1 s for an image,
 * 2 s for a video), `notViewable` when the ad ended before that, `viewUndetermined` when it could not measure.
 * Keeps measuring after the verdict, until the ad ends: it also reports exposure changes, which the page
 * relays to the creative, and notices the view leaving the window.
 */
internal class ViewabilityMeter(view: View, private val requiredMillis: Long) {
    enum class Verdict(val event: String) { VIEWABLE("viewable"), NOT_VIEWABLE("notViewable"), VIEW_UNDETERMINED("viewUndetermined") }

    /** Exposure changed: share on screen (0–1) and the visible rectangle in the view's own pixels. */
    var onExposure: ((Double, Rect) -> Unit)? = null
    var onVerdict: ((Verdict) -> Unit)? = null

    /** The view left the window and did not come back within one interval; on every tick while it stays out, from the second. */
    var onDetached: (() -> Unit)? = null

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
            if (running) handler.postDelayed(this, intervalMillis)
        }
    }
    private var running = false
    private var stopped = false
    private var exposedSince: Long? = null
    private var lastTick: Long? = null
    private var lastPercentage = -1.0
    private var lastVisible = Rect()
    private var detachedTicks = 0

    fun start() {
        if (running || stopped) return
        running = true
        handler.postDelayed(tickRunnable, intervalMillis)
        tick()
    }

    /** The ad ended: a verdict is still owed when none was reached. */
    fun stop() {
        stopped = true
        running = false
        handler.removeCallbacks(tickRunnable)
        if (verdict == null) decide(if (view.get() == null) Verdict.VIEW_UNDETERMINED else Verdict.NOT_VIEWABLE)
    }

    /** One measurement. Internal so tests can drive it with a frozen clock. */
    fun tick() {
        val now = Environment.now()
        val stalled = lastTick?.let { now - it > maxOf(intervalMillis * 2, SHORTEST_STALL_MILLIS) } ?: false
        lastTick = now
        val view = view.get()
        if (view == null || !view.isAttachedToWindow) {
            report(0.0, Rect())
            exposedSince = null
            // One tick of grace: a view re-parented within the same turn is not gone.
            detachedTicks += 1
            if (view != null && detachedTicks >= 2) onDetached?.invoke()
            return
        }
        detachedTicks = 0
        val (percentage, visible) = exposure(view)
        report(percentage, visible)
        if (stopped) return
        if (percentage >= THRESHOLD && countsTime) {
            // A gap between ticks breaks the continuity the rule asks for: the clock starts again.
            if (exposedSince == null || stalled) exposedSince = now
            exposedSince?.let { if (now - it >= requiredMillis) decide(Verdict.VIEWABLE) }
        } else {
            exposedSince = null
        }
    }

    private fun report(percentage: Double, visible: Rect) {
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
        /** How often the box is measured. A constant in spirit; a variable so the tests can shrink it. */
        var intervalMillis = 250L
        const val SHORTEST_STALL_MILLIS = 500L
        const val THRESHOLD = 0.5

        /**
         * The share of [view] the viewer can see and that part in the view's coordinates: clipped by the window
         * and every ancestor (`getGlobalVisibleRect`); nothing when it or an ancestor is not shown or transparent,
         * or its window is not visible. Overlap by sibling views is not seen; nothing cheap sees it.
         */
        fun exposure(view: View): Pair<Double, Rect> {
            if (!view.isShown || view.windowVisibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return 0.0 to Rect()
            var node: Any? = view
            while (node is View) {
                if (node.alpha <= 0.01f) return 0.0 to Rect()
                node = node.parent
            }
            val global = Rect()
            if (!view.getGlobalVisibleRect(global)) return 0.0 to Rect()
            val whole = view.width.toDouble() * view.height
            val seen = global.width().toDouble() * global.height()
            val location = IntArray(2).also(view::getLocationOnScreen)
            val root = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
            // getGlobalVisibleRect is in the root view's coordinates; the page wants the view's own.
            val visible = Rect(global).apply { offset(root[0] - location[0], root[1] - location[1]) }
            if (!visible.intersect(0, 0, view.width, view.height)) visible.setEmpty()
            return (seen / whole).coerceIn(0.0, 1.0) to visible
        }
    }
}
