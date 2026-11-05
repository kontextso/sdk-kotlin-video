package so.kontext.video.internal.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// What the SDK needs to know about a view the app gave it: which Activity it belongs to, and whether it has a real
// size on screen yet.
//
// Why the size: adding a view does not place it. Until Android's next layout pass measures it, its width and
// height are 0, and some containers never give it room at all (a vertical LinearLayout that puts the ad after a
// full-height player). So an image or HTML ad's impression counts from its first layout with a size, not from
// `show`, and the viewability meter measures nothing before it. Like waiting for a ResizeObserver to report a
// real size.

/** The Activity this context belongs to, through any context wrappers; `null` for one of no Activity. */
internal val Context.activity: Activity?
    get() {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

/** The Activity the view is in, through any context wrappers. */
internal val View.activity: Activity? get() = context.activity

/** In a window, laid out and bigger than 0×0. Before that every measurement of the view is 0. */
internal val View.isLaidOutInWindow: Boolean get() = isAttachedToWindow && isLaidOut && width > 0 && height > 0

/**
 * Calls [onLaidOut] once, at the first layout pass after which this view is laid out in a window with an area
 * and [isReady] holds; at once when that is already so. The returned function stops waiting; it does nothing
 * after the call.
 */
internal fun View.awaitLayout(isReady: () -> Boolean = { true }, onLaidOut: () -> Unit): () -> Unit {
    val view = this
    val isLaidOut = { view.isLaidOutInWindow && isReady() }
    if (isLaidOut()) {
        onLaidOut()
        return {}
    }
    // The observer the listener went to: the window's, or the view's own until it is attached.
    val observer = view.viewTreeObserver
    var isWaiting = true
    lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
    val cancel: () -> Unit = {
        isWaiting = false
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        view.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
    }
    listener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!isWaiting || !isLaidOut()) return@OnGlobalLayoutListener
        cancel()
        onLaidOut()
    }
    observer.addOnGlobalLayoutListener(listener)
    return cancel
}

/**
 * How much of this view the phone's own UI covers (status bar, navigation bar, camera notch), from each
 * edge, in the view's pixels: the Android counterpart of iOS's safe area, which iOS gives for free.
 *
 * Android reports the bars for the whole window, but the player may be smaller and sit anywhere in it.
 * So only the part of each bar that reaches into the view counts, never less than 0:
 *
 * ```
 * screen ┌───────────────────────────┐
 *        │▓▓▓▓▓ status bar 24 ▓▓▓▓▓▓▓│ ← overlaps a full-screen player: top = 24
 *        │                           │
 *        │  ┌── player in middle ──┐ │ ← no bar reaches it: all 0
 *        │  └──────────────────────┘ │
 *        │▓▓▓▓▓▓ nav bar 48 ▓▓▓▓▓▓▓▓▓│
 *        └───────────────────────────┘
 * ```
 *
 * A full-screen player: the status bar overlaps its top, so the ad stays below it. A player in the middle
 * of the screen: nothing overlaps, and the ad may use all of it. Not attached yet: all 0. Like CSS
 * `env(safe-area-inset-*)`, but for one view. `place` adds the app's own insets to these.
 */
internal val View.systemInsets: Rect
    get() {
        // 1. How far the bars and the notch reach in from the window's edges.
        val insets = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return Rect()
        // 2. Where this view sits in the window.
        val root = rootView
        val location = IntArray(2).also(::getLocationInWindow)
        val x = location[0]
        val y = location[1]
        // 3. How far each of them reaches into this view.
        return Rect(
            maxOf(0, insets.left - x),
            maxOf(0, insets.top - y),
            maxOf(0, x + width - (root.width - insets.right)),
            maxOf(0, y + height - (root.height - insets.bottom)),
        )
    }
