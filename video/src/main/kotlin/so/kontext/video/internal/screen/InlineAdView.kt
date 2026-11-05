package so.kontext.video.internal.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * What an overlay or pause ad puts into the player's container: a transparent layer over the whole
 * container that holds the creative at its place. Covering the container is what lets it follow a resize or
 * a rotation; touches outside the creative pass through to the player (not with
 * `coversWhole`: a full-screen ad whose page sits inside the bars, the host opaque and taking touches around it).
 *
 * ```
 * ┌───────── InlineAdView = the whole player, e.g. 800×400 ─────────┐
 * │                                                                 │
 * │                (transparent, taps pass through)                 │
 * │                                                                 │
 * │                  ┌──── WebView 320×80 ────┐                     │
 * │                  └────────────────────────┘                     │
 * │                      ↑ above the bottom inset                   │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * The WebView is only as big as the ad (its size from the server, shrunk at most to half when it does not fit),
 * placed by [place] at every layout pass, so it follows a resize or a rotation.
 */
@SuppressLint("ViewConstructor")
internal class InlineAdView(context: Context, val content: View, coversWhole: Boolean = false) : FrameLayout(context) {
    /** The creative's frame for the container's size and insets, in this view's pixels; `null` when it no longer fits. */
    var place: ((width: Int, height: Int, insets: Rect) -> Rect?)? = null

    /** The container became too small for the creative. Once. */
    var onUnfit: (() -> Unit)? = null

    /** A layout pass has placed the creative in a frame with an area. */
    var hasPlacedContent = false
        private set

    /** The window holding the ad was shown or hidden (the Activity started or stopped). */
    var onWindowVisibility: ((Boolean) -> Unit)? = null

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        if (coversWhole) {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        addView(content, LayoutParams(0, 0))
    }

    /** Called by Android at every layout pass (first show, rotation, resize, bars shown or hidden): places the ad. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 1. Ask VideoAd: for this size and the system bars, where does the ad go?
        val frame = place?.invoke(right - left, bottom - top, systemInsets)
        // 2. It no longer fits: tell VideoAd, once (it ends the ad). Posted, not called inside the layout pass:
        //    ending removes the view being laid out.
        if (frame == null) {
            onUnfit?.let { unfit ->
                onUnfit = null
                post(unfit)
            }
            return
        }
        // 3. It fits: give the WebView exactly that size, and put it there.
        measureContent(frame)
        content.layout(frame.left, frame.top, frame.right, frame.bottom)
        // 4. The ad now has a place with a size: awaitLayout waits for this before the impression counts.
        if (!frame.isEmpty) hasPlacedContent = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val frame = place?.invoke(measuredWidth, measuredHeight, systemInsets) ?: return
        measureContent(frame)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onWindowVisibility?.invoke(visibility == View.VISIBLE)
    }

    /** The WebView gets exactly the size of its [frame]. */
    private fun measureContent(frame: Rect) {
        content.measure(MeasureSpec.makeMeasureSpec(frame.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(frame.height(), MeasureSpec.EXACTLY))
    }
}
