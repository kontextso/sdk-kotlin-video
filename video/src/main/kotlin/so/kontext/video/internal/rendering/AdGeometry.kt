package so.kontext.video.internal.rendering

import android.graphics.Rect
import so.kontext.video.internal.payloads.AdPayload
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The geometry of an overlay or pause ad in the player's view, as pure arithmetic in pixels: no views and no ad,
 * only rectangles and scales. Where the ad goes: a variant scaled down to fit the view less its insets, never
 * enlarged and never below its smallest scale, centred, at the top or the bottom. Sizes come in dp and are turned
 * into pixels with the display density. The ad's own choices (which variant, which insets) are in
 * `VideoAdPlacement`; whether the viewer could see a view is in `ViewabilityMeter`.
 */
internal object AdGeometry {
    /** Below this share of the nominal size a scalable ad is not shown. */
    const val MINIMUM_SCALE = 0.5

    /** Where [size] goes inside [width]×[height] less [insets]; `null` when it does not fit. */
    fun frame(size: AdPayload.Size, position: AdPayload.Position?, width: Int, height: Int, insets: Rect, density: Float): Rect? {
        // 1. The free space: the view less the insets. Nothing left: it does not fit.
        val room = room(width, height, insets) ?: return null
        // 2. The largest scale at which the ad fits that space, at most 1 (full size, never enlarged).
        val scale = scale(size, room, density)
        // 3. It would have to shrink below its limit (half, or not at all for a fixed ad): it does not fit.
        if (scale < smallestScale(size)) return null
        // 4. Its size in pixels at that scale: dp × density × scale.
        val scaledWidth = (size.width * density * scale).roundToInt()
        val scaledHeight = (size.height * density * scale).roundToInt()
        // 5. Centred across, at the top or the bottom (the default) of the free space.
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
}
