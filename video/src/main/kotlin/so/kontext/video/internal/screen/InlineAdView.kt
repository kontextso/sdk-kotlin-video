package so.kontext.video.internal.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
 *
 * [place] already keeps the creative clear of the bars and the cutout, so the page is told of none: Android's
 * WebView would report the window's whole bars as CSS `env(safe-area-inset-*)` wherever it sits, and a page
 * that pads by them (a full-screen pause ad's badge and close button) would count them twice. As on iOS, where
 * a web view inside the safe area reads 0 there.
 */
@SuppressLint("ViewConstructor")
internal class InlineAdView(context: Context, val content: View, coversWhole: Boolean = false) : FrameLayout(context) {
    /** The creative's frame for the container's size and insets, in this view's pixels; `null` when it no longer fits. */
    var place: ((width: Int, height: Int, insets: Rect) -> Rect?)? = null

    /**
     * The container became too small for the creative, and still was [unfitGraceMillis] later: a single pass
     * (a split-screen or keyboard resize, an animated size, a rotation's intermediate pass) does not end the ad.
     * Once.
     */
    var onUnfit: (() -> Unit)? = null

    /** The check, after the grace, of a pass where the creative did not fit; cleared by a pass where it fits. */
    private var unfitCheck: Runnable? = null

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
        // The page sees no system insets: it is already placed clear of them (see the class comment). Handed the
        // consumed insets rather than none, so a WebView that read the bars before forgets them.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, _ ->
            ViewCompat.onApplyWindowInsets(view, WindowInsetsCompat.CONSUMED)
            WindowInsetsCompat.CONSUMED
        }
        addView(content, LayoutParams(0, 0))
    }

    /** Called by Android at every layout pass (first show, rotation, resize, bars shown or hidden): places the ad. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 1. Ask VideoAd: for this size and the system bars, where does the ad go?
        val frame = place?.invoke(right - left, bottom - top, systemInsets)
        // 2. It no longer fits: asked again after the grace, with the size then; still too small, VideoAd hears it,
        //    once (it ends the ad). Never inside the layout pass: ending removes the view being laid out.
        if (frame == null) {
            if (onUnfit != null && unfitCheck == null) {
                val check = Runnable {
                    unfitCheck = null
                    if (place?.invoke(width, height, systemInsets) != null) {
                        requestLayout()
                        return@Runnable
                    }
                    onUnfit?.let { unfit ->
                        onUnfit = null
                        unfit()
                    }
                }
                unfitCheck = check
                postDelayed(check, unfitGraceMillis)
            }
            return
        }
        // 3. It fits (again): a pending check is dropped; the WebView gets exactly that size, and goes there.
        unfitCheck?.let(::removeCallbacks)
        unfitCheck = null
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

    companion object {
        /** How long the creative may not fit before the ad ends: Swift's grace. A constant in spirit; tests set it. */
        var unfitGraceMillis = 500L
    }
}
