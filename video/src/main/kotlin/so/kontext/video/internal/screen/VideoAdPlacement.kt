package so.kontext.video.internal.screen

import android.graphics.Rect
import so.kontext.video.VideoAdException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.support.Logger

// Where an overlay or pause ad goes in the app's container. Two layers: this file decides, AdGeometry calculates.
//
//   VideoAd.show(container, insets)  ─┐
//   InlineAdView.onLayout (each pass)  ─┤
//                                     ▼
//   ┌─ VideoAdPlacement (knows this ad) ────────────────────────────┐
//   │  combinedInsets  system bars + the app's insets               │
//   │  chooseSize      which of the server's variants   (at show)   │
//   │  refit           where it goes now                (each pass) │
//   │  tooSmall        the ContainerTooSmall error                  │
//   │  clearOfBars     full-screen pause ad: all but the bars        │
//   └──────────────────────────────┬────────────────────────────────┘
//                                  │ asks with plain numbers
//                                  ▼
//   ┌─ AdGeometry (knows no ad, pure arithmetic) ───────────────────┐
//   │  room           the container less the insets                 │
//   │  scale          how much the ad must shrink, at most 1        │
//   │  smallestScale  0.5, or 1 for a fixed ad                      │
//   │  frame          the rectangle: room, scale, check, place      │
//   │  minimum        the smallest container that would do          │
//   └───────────────────────────────────────────────────────────────┘
//
// 1. At show, once: chooseSize. AdGeometry.room gives the free space (none: ContainerTooSmall). For each size
//    variant from the server AdGeometry.scale says how much it would shrink; the pick is the biggest variant that
//    fits at full size, else the one that shrinks least within its limit, else the closest (which then fails).
//    AdGeometry.frame checks it; null: tooSmall reports AdGeometry.minimum and show throws, the ad stays ready.
//    The pick is kept as the inline screen's chosenSize, next to the app's insets (extraInsets).
// 2. At every layout pass of InlineAdView: refit. A full-screen pause ad takes the container less the bars, no
//    variants. Otherwise AdGeometry.frame(chosenSize, …, combinedInsets(system)) gives where InlineAdView puts the
//    WebView; null: it no longer fits, InlineAdView calls onUnfit and the ad ends INTERRUPTED.
//
// Example: variants 320×80 and 300×50 dp, density 3, a player of 800×600 px, nav bar 48 px, app controls 120 px.
//   combinedInsets: bottom 168, so room is 800×432.
//   chooseSize: 320×80 is 960×240 px, fits at 0.83; 300×50 is 900×150 px, fits at 0.89. Neither fits at full
//   size, so the one that shrinks least: 300×50.
//   refit: 300×50 at 0.89 is 800×133 px, centred, just above the bottom 168 px. A rotation runs refit again, same
//   variant, new numbers.
//
// The split keeps AdGeometry testable with numbers alone, and the ad's rules (variants, full screen, errors,
// logs) here.

/** The system's and the app's insets together; saturated, so a huge app inset cannot wrap around to a small one. */
internal fun AdScreen.InlineScreen.combinedInsets(system: Rect): Rect {
    fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return Rect(add(system.left, extraInsets.left), add(system.top, extraInsets.top), add(system.right, extraInsets.right), add(system.bottom, extraInsets.bottom))
}

/** Picks the variant for the container: the biggest that fits unscaled, else the one shrunk least. Throws `ContainerTooSmall`. */
internal fun AdScreen.InlineScreen.chooseSize(width: Int, height: Int, insets: Rect, density: Float) {
    val payload = ad.payload
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

private fun AdScreen.InlineScreen.tooSmall(size: AdPayload.Size, insets: Rect, density: Float, what: String): VideoAdException {
    val (minWidth, minHeight) = AdGeometry.minimum(size, insets, density)
    val limit = if (size.isScalable) " at 50 %" else ", which may not shrink"
    Logger.warning { "ad ${ad.id.take(8)}: $what the ad's ${size.width}×${size.height} dp$limit; the ad stays ready" }
    return VideoAdException.ContainerTooSmall(minWidth, minHeight)
}

internal fun AdScreen.InlineScreen.refit(width: Int, height: Int, system: Rect, density: Float): Rect? {
    val payload = ad.payload
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
