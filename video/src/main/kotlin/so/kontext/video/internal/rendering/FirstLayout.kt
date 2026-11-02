package so.kontext.video.internal.rendering

import android.view.View
import android.view.ViewTreeObserver

/**
 * Waits for [view]'s first layout in a window with an area: the moment it can first be drawn. An image or
 * HTML creative's impression counts from there, not from `show`: a container that gives the ad no room (a
 * vertical LinearLayout appending it after the player) never draws it.
 */
internal object FirstLayout {
    /** Laid out in a window with an area. Before that every measurement of the view is 0 for want of a layout. */
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
