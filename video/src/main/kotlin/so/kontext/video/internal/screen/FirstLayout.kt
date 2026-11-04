package so.kontext.video.internal.screen

import android.view.View
import android.view.ViewTreeObserver

/**
 * Has the ad's view really appeared on screen, with a size? And if not yet, a way to wait until it has.
 *
 * Why: adding a view does not place it. Until Android's next layout pass measures it, its width and height are
 * 0, and some containers never give it room at all (a vertical LinearLayout that puts the ad after a full-height
 * player). So an image or HTML ad's impression counts from its first layout with a size, not from `show`, and
 * the viewability meter measures nothing before it. Like waiting for a ResizeObserver to report a real size.
 */
internal object FirstLayout {
    /** In a window, laid out and bigger than 0×0. Before that every measurement of the view is 0. */
    fun isLaidOutInWindow(view: View): Boolean = view.isAttachedToWindow && view.isLaidOut && view.width > 0 && view.height > 0

    /**
     * Calls [onLaidOut] once, at the first layout pass after which [view] is laid out in a window with an area
     * and [isReady] holds; at once when that is already so. The returned function stops waiting; it does
     * nothing after the call.
     */
    fun await(view: View, isReady: () -> Boolean = { true }, onLaidOut: () -> Unit): () -> Unit {
        val laidOut = { isLaidOutInWindow(view) && isReady() }
        if (laidOut()) {
            onLaidOut()
            return {}
        }
        // The observer the listener went to: the window's, or the view's own until it is attached.
        val observer = view.viewTreeObserver
        var waiting = true
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
        val cancel: () -> Unit = {
            waiting = false
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            view.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!waiting || !laidOut()) return@OnGlobalLayoutListener
            cancel()
            onLaidOut()
        }
        observer.addOnGlobalLayoutListener(listener)
        return cancel
    }
}
