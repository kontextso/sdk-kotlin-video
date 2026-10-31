package so.kontext.video.internal.rendering

import android.graphics.Rect
import android.view.View
import so.kontext.video.internal.wire.AdPayload
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

    /** The smallest view, in pixels and with [insets], that holds [size]. */
    fun minimum(size: AdPayload.Size, insets: Rect, density: Float): Pair<Int, Int> {
        val smallest = smallestScale(size)
        return (ceil(size.width * density * smallest).toInt() + insets.left + insets.right) to
            (ceil(size.height * density * smallest).toInt() + insets.top + insets.bottom)
    }

    /** Whether the viewer could see [view]: attached, laid out with an area, shown, and nothing on the way transparent. */
    fun canBeSeen(view: View): Boolean {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 || !view.isShown) return false
        if (view.windowVisibility != View.VISIBLE) return false
        var node: Any? = view
        while (node is View) {
            if (node.alpha <= 0.01f) return false
            node = node.parent
        }
        return true
    }
}
