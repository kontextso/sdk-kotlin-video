package so.kontext.video.internal.measurement

import android.graphics.Matrix
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How much of a view the viewer can see: its corners carried through every ancestor's transform (rotation,
 * scale, scroll), the shape cut by every ancestor that clips its children and by the window; visible area over
 * full area is the share. Real shapes, not rectangles, so a rotated view is not over-counted ([Polygon]).
 */
internal object ViewExposure {
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
    fun measure(view: View): Exposure {
        if (isHidden(view)) return Exposure.NONE
        val toRoot = transformToRoot(view)
        val outline = Polygon.corners(view.width.toFloat(), view.height.toFloat()).also(toRoot::mapPoints)
        val whole = Polygon.area(outline)
        if (whole <= 0) return Exposure.NONE
        val visible = clippedByAncestors(view, outline)
        val seen = Polygon.area(visible)
        if (seen <= 0) return Exposure.NONE
        // The visible part back in the view's own coordinates, for the page.
        val inView = visible.copyOf().also(Matrix().also { toRoot.invert(it) }::mapPoints)
        val xs = inView.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = inView.filterIndexed { index, _ -> index % 2 == 1 }
        val box = Rect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
        if (!box.intersect(0, 0, view.width, view.height)) box.setEmpty()
        return Exposure((seen / whole).coerceIn(0.0, 1.0), box)
    }

    /** Not shown, without an area, in a window that is not visible, or transparent: its alpha times its ancestors'. */
    private fun isHidden(view: View): Boolean {
        if (!view.isShown || view.windowVisibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return true
        return isTransparent(view)
    }

    /** [outline], in the root's coordinates, cut by the window and by every ancestor that clips its children. */
    private fun clippedByAncestors(view: View, outline: FloatArray): FloatArray {
        val root = view.rootView
        var visible = Polygon.clip(outline, Polygon.corners(root.width.toFloat(), root.height.toFloat()))
        var child: View = view
        while (visible.size >= 6) {
            val parent = child.parent as? ViewGroup ?: break
            val toRoot by lazy { transformToRoot(parent) }
            if (parent.clipChildren) {
                visible = Polygon.clip(visible, Polygon.corners(parent.width.toFloat(), parent.height.toFloat()).also(toRoot::mapPoints))
            }
            // Its children draw inside its padding too (clipToPadding, on by default), whatever clipChildren says.
            if (parent.clipToPadding && (parent.paddingLeft or parent.paddingTop or parent.paddingRight or parent.paddingBottom) != 0) {
                visible = Polygon.clip(visible, paddedArea(parent).also(toRoot::mapPoints))
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
}

/** The share of a view on screen (0–1), and the bounding rectangle of the part seen, in the view's own pixels. */
internal data class Exposure(val share: Double, val visibleBox: Rect) {
    companion object {
        /** Nothing seen. A new Rect each time: a Rect is mutable. */
        val NONE: Exposure get() = Exposure(0.0, Rect())
    }
}
