package so.kontext.video.internal.rendering

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import so.kontext.video.internal.support.Environment
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A measuring loop: every 250 ms ([intervalMillis]) it asks how much of the ad's view the viewer can see right
 * now, and turns the answer into a few signals. It decides the one viewability event, the industry's (MRC)
 * rule: `viewable` once at least 50 % of the ad was on screen without a break for 1 s (2 s for a video),
 * `notViewable` when the ad ended before that, `viewUndetermined` when it could not measure.
 *
 * ```
 * every 250 ms: tick()
 *   ├─ measure:   exposure(view): share on screen (0–1) and the visible rectangle   (companion: geometry)
 *   ├─ report:    changed? onExposure(share, rect)       → the page, which tells the creative
 *   │             any of it seen? changed: onSeen(bool)  → ActiveTimeClock
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
 * The two hard parts. The geometry, `exposure` in the companion: the view's corners are carried through every
 * ancestor's transform (rotation, scale, scroll), and the shape is cut by every ancestor that clips its children
 * and by the window; visible area over full area is the share. Real shapes, not rectangles, so a rotated view
 * is not over-counted. The timing, [countDwell]: `exposedSince` marks when the ad reached 50 %, and starts again
 * when the share drops, the video pauses, or the loop stalled (a frozen app cannot prove the time was
 * continuous).
 *
 * Safety: nothing is reported before the first layout ([measured]), where every measurement is 0 only for want
 * of a size and a page would pause its video for nothing; one tick of grace before [onDetached], since a view
 * moved to another parent in the same moment is not gone; the verdict is decided once.
 *
 * Not ActiveTimeClock: that one adds up any time seen, with breaks; this one judges the 50 % rule.
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

    /**
     * The view has been laid out in a window with an area once. Before that every measurement is 0 for want
     * of a layout, not because the viewer cannot see the ad, and a page that pauses its video on exposure 0
     * would pause and resume at once: nothing is reported until then.
     */
    private var measured = false

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
        // 1. Did the loop stall (more than two ticks, and at least 500 ms, since the last: the app froze or slept)?
        //    Then the 50 % stretch cannot be called continuous, and countDwell starts it again.
        val now = Environment.now()
        val stalled = lastTick?.let { now - it > maxOf(intervalMillis * 2, SHORTEST_STALL_MILLIS) } ?: false
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
        if (stopped) return
        // 5. Nothing seen for a second while the app is in front: onOffScreen, the ad ends.
        checkOffScreen(now)
        // 6. The 50 % rule: the continuous time at 50 % or more; long enough, the verdict VIEWABLE, once.
        if (!stopped) countDwell(percentage, now, stalled)
    }

    /** The MRC rule's continuous seconds at 50 % or more; a gap between ticks starts them again. */
    private fun countDwell(percentage: Double, now: Long, stalled: Boolean) {
        if (percentage >= THRESHOLD && countsTime) {
            if (exposedSince == null || stalled) exposedSince = now
            exposedSince?.let { if (now - it >= requiredMillis) decide(Verdict.VIEWABLE) }
        } else {
            exposedSince = null
        }
    }

    /** The view's exposure, or none while something covers it. */
    private fun seen(view: View): Pair<Double, Rect> = if (isCovered?.invoke() == true) 0.0 to Rect() else exposure(view)

    private fun checkOffScreen(now: Long) {
        if (!measured || isSeen || !isActive()) {
            unseenSince = null
            return
        }
        val since = unseenSince ?: now.also { unseenSince = it }
        if (now - since >= offScreenGraceMillis) onOffScreen?.invoke()
    }

    private fun report(percentage: Double, visible: Rect) {
        if (!measured) {
            val view = view.get() ?: return
            if (!FirstLayout.isLaidOutInWindow(view)) return
            measured = true
        }
        val seen = percentage > 0
        if (seen != isSeen) {
            isSeen = seen
            onSeen?.invoke(seen)
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

        /**
         * At most this opacity (1 %) a view counts as invisible, though it is shown: its alpha times every
         * ancestor's, as Android draws it. Not 0: three ancestors at 0.1 make 0.001, which nobody can see, yet no
         * single view is transparent; an ad drawn like that (by accident, or to fake views) must not be shown, get
         * an impression or count as seen. 1 % is invisible to the eye, and far below any fade a real UI rests at.
         */
        private const val TRANSPARENT = 0.01f

        /** Whether the viewer could see [view]: attached, laid out with an area, shown, and not transparent (alpha times its ancestors'). */
        fun canBeSeen(view: View): Boolean {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 || !view.isShown) return false
            if (view.windowVisibility != View.VISIBLE) return false
            return !isTransparent(view)
        }

        /**
         * Whether [view] draws at no visible opacity: its alpha times each ancestor's, as Android composes them, at most
         * 1 %. Three ancestors at 0.1 make 0.001, transparent, though none of them is alone.
         */
        fun isTransparent(view: View): Boolean {
            var alpha = 1f
            var node: Any? = view
            while (node is View) {
                alpha *= node.alpha
                node = node.parent
            }
            return alpha <= TRANSPARENT
        }

        /** The view's own coordinates to the root view's: each ancestor's transform and position, up to the root. */
        fun transformToRoot(view: View): Matrix {
            val matrix = Matrix()
            var node: View = view
            while (true) {
                matrix.postConcat(node.matrix)
                matrix.postTranslate(node.left.toFloat(), node.top.toFloat())
                val parent = node.parent as? View ?: break
                matrix.postTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
                node = parent
            }
            return matrix
        }

        /**
         * The share of [view] the viewer can see, and that part's bounding rectangle in the view's own pixels: its
         * outline, transformed to the root's coordinates, cut by the window and by every ancestor that clips its
         * children. Measured on the actual shapes, not on rectangles: a rotated view or ancestor turns a rectangle
         * into a rotated one, whose bounding box would overstate what is seen. Nothing when it or an ancestor is
         * not shown or transparent, or its window is not visible. Overlap by sibling views is not seen; nothing
         * cheap sees it.
         */
        fun exposure(view: View): Pair<Double, Rect> {
            if (isHidden(view)) return 0.0 to Rect()
            val toRoot = transformToRoot(view)
            val outline = corners(view.width.toFloat(), view.height.toFloat()).also(toRoot::mapPoints)
            val whole = area(outline)
            if (whole <= 0) return 0.0 to Rect()
            val visible = clippedByAncestors(view, outline)
            val seen = area(visible)
            if (seen <= 0) return 0.0 to Rect()
            // The visible part back in the view's own coordinates, for the page.
            val inView = visible.copyOf().also(Matrix().also { toRoot.invert(it) }::mapPoints)
            val xs = inView.filterIndexed { index, _ -> index % 2 == 0 }
            val ys = inView.filterIndexed { index, _ -> index % 2 == 1 }
            val box = Rect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
            if (!box.intersect(0, 0, view.width, view.height)) box.setEmpty()
            return (seen / whole).coerceIn(0.0, 1.0) to box
        }

        /** Not shown, without an area, in a window that is not visible, or transparent: its alpha times its ancestors'. */
        private fun isHidden(view: View): Boolean {
            if (!view.isShown || view.windowVisibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return true
            return isTransparent(view)
        }

        /** [outline], in the root's coordinates, cut by the window and by every ancestor that clips its children. */
        private fun clippedByAncestors(view: View, outline: FloatArray): FloatArray {
            val root = view.rootView
            var visible = clip(outline, corners(root.width.toFloat(), root.height.toFloat()))
            var child: View = view
            while (visible.size >= 6) {
                val parent = child.parent as? ViewGroup ?: break
                val toRoot by lazy { transformToRoot(parent) }
                if (parent.clipChildren) {
                    visible = clip(visible, corners(parent.width.toFloat(), parent.height.toFloat()).also(toRoot::mapPoints))
                }
                // Its children draw inside its padding too (clipToPadding, on by default), whatever clipChildren says.
                if (parent.clipToPadding && (parent.paddingLeft or parent.paddingTop or parent.paddingRight or parent.paddingBottom) != 0) {
                    visible = clip(visible, paddedArea(parent).also(toRoot::mapPoints))
                }
                child = parent
            }
            return visible
        }

        /** [group]'s area inside its padding, as a polygon in its own coordinates. */
        private fun paddedArea(group: ViewGroup): FloatArray {
            val left = group.paddingLeft.toFloat()
            val top = group.paddingTop.toFloat()
            val right = (group.width - group.paddingRight).toFloat()
            val bottom = (group.height - group.paddingBottom).toFloat()
            // Padding that leaves no room clips everything.
            if (right <= left || bottom <= top) return FloatArray(0)
            return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
        }

        /** A width×height rectangle at the origin as a polygon: x0, y0, x1, y1, … */
        private fun corners(width: Float, height: Float) = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)

        /** The area of a polygon (shoelace formula), whatever its winding. */
        private fun area(polygon: FloatArray): Double = abs(signedArea(polygon))

        private fun signedArea(polygon: FloatArray): Double {
            val count = polygon.size / 2
            if (count < 3) return 0.0
            var twice = 0.0
            for (index in 0 until count) {
                val next = (index + 1) % count
                twice += polygon[index * 2].toDouble() * polygon[next * 2 + 1] - polygon[next * 2].toDouble() * polygon[index * 2 + 1]
            }
            return twice / 2
        }

        /**
         * [subject] cut by the convex polygon [clipper] (Sutherland–Hodgman). Every outline here is the image of a
         * rectangle under an affine transform, so the clipper is always convex.
         */
        private fun clip(subject: FloatArray, clipper: FloatArray): FloatArray {
            val clipCount = clipper.size / 2
            if (clipCount < 3) return FloatArray(0)
            // The clipper's winding decides which side of each edge is inside.
            val sign = if (signedArea(clipper) >= 0) 1.0 else -1.0
            var output = subject
            for (edge in 0 until clipCount) {
                if (output.isEmpty()) break
                val next = (edge + 1) % clipCount
                output = clipByEdge(output, clipper[edge * 2], clipper[edge * 2 + 1], clipper[next * 2], clipper[next * 2 + 1], sign)
            }
            return output
        }

        /** [input] less what lies outside the edge a→b. */
        private fun clipByEdge(input: FloatArray, ax: Float, ay: Float, bx: Float, by: Float, sign: Double): FloatArray {
            val inside = { x: Double, y: Double -> sign * ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) >= 0 }
            val output = mutableListOf<Float>()
            val count = input.size / 2
            for (index in 0 until count) {
                val cx = input[index * 2].toDouble()
                val cy = input[index * 2 + 1].toDouble()
                val previous = (index + count - 1) % count
                val px = input[previous * 2].toDouble()
                val py = input[previous * 2 + 1].toDouble()
                val currentIn = inside(cx, cy)
                if (currentIn != inside(px, py)) {
                    // Where the segment previous→current crosses the edge's line.
                    val dx = cx - px
                    val dy = cy - py
                    val denominator = dx * (by - ay) - dy * (bx - ax)
                    val t = if (abs(denominator) < 1e-12) 1.0 else ((ax - px) * (by - ay) - (ay - py) * (bx - ax)) / denominator
                    output += (px + t * dx).toFloat()
                    output += (py + t * dy).toFloat()
                }
                if (currentIn) {
                    output += cx.toFloat()
                    output += cy.toFloat()
                }
            }
            return output.toFloatArray()
        }
    }
}
