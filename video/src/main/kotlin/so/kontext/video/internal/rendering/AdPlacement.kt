package so.kontext.video.internal.rendering

import android.graphics.Rect
import android.view.View
import so.kontext.video.internal.payloads.AdPayload
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where an overlay or pause ad goes in the player's view, as pure arithmetic in pixels: a variant scaled down
 * to fit the view less its insets, never enlarged and never below its smallest scale, centred, at the top or
 * the bottom. Sizes come in dp and are turned into pixels with the display density.
 */
internal object AdPlacement {
    /** Below this share of the nominal size a scalable ad is not shown. */
    const val MINIMUM_SCALE = 0.5

    /**
     * At most this opacity (1 %) a view counts as invisible, though it is shown: its alpha times every
     * ancestor's, as Android draws it. Not 0: three ancestors at 0.1 make 0.001, which nobody can see, yet no
     * single view is transparent; an ad drawn like that (by accident, or to fake views) must not be shown, get
     * an impression or count as seen. 1 % is invisible to the eye, and far below any fade a real UI rests at.
     */
    private const val TRANSPARENT = 0.01f

    /** Where [size] goes inside [width]×[height] less [insets]; `null` when it does not fit. */
    fun frame(size: AdPayload.Size, position: AdPayload.Position?, width: Int, height: Int, insets: Rect, density: Float): Rect? {
        val room = room(width, height, insets) ?: return null
        val scale = scale(size, room, density)
        if (scale < smallestScale(size)) return null
        val scaledWidth = (size.width * density * scale).roundToInt()
        val scaledHeight = (size.height * density * scale).roundToInt()
        val left = room.left + (room.width() - scaledWidth) / 2
        val top = if (position == AdPayload.Position.TOP) room.top else room.bottom - scaledHeight
        return Rect(left, top, left + scaledWidth, top + scaledHeight)
    }

    /** What [insets] leave of the view; `null` when they leave no area. */
    fun room(width: Int, height: Int, insets: Rect): Rect? {
        val roomWidth = width.toLong() - insets.left - insets.right
        val roomHeight = height.toLong() - insets.top - insets.bottom
        if (roomWidth <= 0 || roomHeight <= 0) return null
        return Rect(insets.left, insets.top, width - insets.right, height - insets.bottom)
    }

    /** The scale at which [size] fits [room], at most 1: never enlarged. */
    fun scale(size: AdPayload.Size, room: Rect, density: Float): Double =
        min(1.0, min(room.width() / (size.width * density.toDouble()), room.height() / (size.height * density.toDouble())))

    fun smallestScale(size: AdPayload.Size): Double = if (size.scalable) MINIMUM_SCALE else 1.0

    /** The smallest view, in pixels and with [insets], that holds [size]; at most `Int.MAX_VALUE` for insets that large. */
    fun minimum(size: AdPayload.Size, insets: Rect, density: Float): Pair<Int, Int> {
        val smallest = smallestScale(size)
        fun sum(ad: Double, before: Int, after: Int) = (ceil(ad).toLong() + before + after).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return sum(size.width * density * smallest, insets.left, insets.right) to sum(size.height * density * smallest, insets.top, insets.bottom)
    }

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
}
