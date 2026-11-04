package so.kontext.video.internal.rendering

import android.graphics.Rect
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.support.Logger

// Where an overlay or pause ad goes in the app's container, at `show` and at every resize: the size variant,
// its scale, and the frame inside the system bars, the cutout and the app's insets.

/** The system's and the app's insets together; saturated, so a huge app inset cannot wrap around to a small one. */
internal fun VideoAd.combinedInsets(system: Rect): Rect {
    fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return Rect(add(system.left, extraInsets.left), add(system.top, extraInsets.top), add(system.right, extraInsets.right), add(system.bottom, extraInsets.bottom))
}

/** Picks the variant for the container: the biggest that fits unscaled, else the one shrunk least. Throws `ContainerTooSmall`. */
internal fun VideoAd.chooseSize(width: Int, height: Int, insets: Rect, density: Float) {
    val defaultSize = payload.sizes.firstOrNull()
    if (payload.position == AdPayload.Position.FULLSCREEN || defaultSize == null) return
    val room = AdGeometry.room(width, height, insets) ?: run {
        chosenSize = null
        throw tooSmall(defaultSize, insets, density, "the insets leave no room for")
    }
    val fit = { candidate: AdPayload.Size -> AdGeometry.scale(candidate, room, density) }
    val size = payload.sizes.filter { fit(it) >= 1 }.maxByOrNull { it.width * it.height }
        ?: payload.sizes.filter { fit(it) >= AdGeometry.smallestScale(it) }.maxByOrNull(fit)
        ?: payload.sizes.maxByOrNull(fit)
        ?: defaultSize
    chosenSize = size
    if (AdGeometry.frame(size, payload.position, width, height, insets, density) == null) {
        chosenSize = null
        throw tooSmall(size, insets, density, "a $width×$height px container is too small for")
    }
}

private fun VideoAd.tooSmall(size: AdPayload.Size, insets: Rect, density: Float, what: String): VideoAdException {
    val (minWidth, minHeight) = AdGeometry.minimum(size, insets, density)
    val limit = if (size.scalable) " at 50 %" else ", which may not shrink"
    Logger.warning { "ad ${id.take(8)}: $what the ad's ${size.width}×${size.height} dp$limit; the ad stays ready" }
    return VideoAdException.ContainerTooSmall(minWidth, minHeight)
}

internal fun VideoAd.refit(width: Int, height: Int, system: Rect, density: Float): Rect? {
    // A full-screen pause ad clears the system bars and the cutout: Android's WebView gives the page no
    // safe-area insets, so a page laid out edge to edge would put its controls under the clock. The host
    // still covers the whole container behind the bars.
    if (payload.position == AdPayload.Position.FULLSCREEN) return clearOfBars(width, height, system)
    val size = chosenSize ?: return Rect(0, 0, width, height)
    return AdGeometry.frame(size, payload.position, width, height, combinedInsets(system), density)
}

/** The container less the system bars and cutout that overlap it; the whole container when they leave no room. */
private fun clearOfBars(width: Int, height: Int, system: Rect): Rect {
    val frame = Rect(system.left, system.top, width - system.right, height - system.bottom)
    return if (frame.width() > 0 && frame.height() > 0) frame else Rect(0, 0, width, height)
}
