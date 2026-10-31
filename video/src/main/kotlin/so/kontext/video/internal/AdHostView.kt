package so.kontext.video.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * What an overlay or pause ad puts into the player's container: a transparent layer over the whole
 * container that holds the creative at its place. Covering the container is what lets it follow a resize or
 * a rotation; touches outside the creative pass through to the player.
 */
@SuppressLint("ViewConstructor")
internal class AdHostView(context: Context, val content: View) : FrameLayout(context) {
    /** The creative's frame for the container's size and insets, in this view's pixels; `null` when it no longer fits. */
    var place: ((width: Int, height: Int, insets: Rect) -> Rect?)? = null

    /** The container became too small for the creative. Once. */
    var onUnfit: (() -> Unit)? = null

    /** The window holding the ad was shown or hidden (the Activity started or stopped). */
    var onWindowVisibility: ((Boolean) -> Unit)? = null

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        addView(content, LayoutParams(0, 0))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val frame = place?.invoke(right - left, bottom - top, systemInsets(this))
        if (frame == null) {
            onUnfit?.let { unfit ->
                onUnfit = null
                // Not inside the layout pass: ending removes the view being laid out.
                post(unfit)
            }
            return
        }
        content.measure(MeasureSpec.makeMeasureSpec(frame.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(frame.height(), MeasureSpec.EXACTLY))
        content.layout(frame.left, frame.top, frame.right, frame.bottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val frame = place?.invoke(measuredWidth, measuredHeight, systemInsets(this)) ?: return
        content.measure(MeasureSpec.makeMeasureSpec(frame.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(frame.height(), MeasureSpec.EXACTLY))
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onWindowVisibility?.invoke(visibility == View.VISIBLE)
    }

    companion object {
        /**
         * The part of the system bars and display cutout that overlaps [view], in its pixels: the Android
         * counterpart of a safe area. Zero where the bars do not reach the view.
         */
        fun systemInsets(view: View): Rect {
            val insets = ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return Rect()
            val root = view.rootView
            val location = IntArray(2).also(view::getLocationInWindow)
            val x = location[0]
            val y = location[1]
            return Rect(
                maxOf(0, insets.left - x),
                maxOf(0, insets.top - y),
                maxOf(0, x + view.width - (root.width - insets.right)),
                maxOf(0, y + view.height - (root.height - insets.bottom)),
            )
        }
    }
}
