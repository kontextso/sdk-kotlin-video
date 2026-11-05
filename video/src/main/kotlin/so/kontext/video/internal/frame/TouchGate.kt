package so.kontext.video.internal.frame

/**
 * The touch a link needs: a page may open a URL (a click, an open, a navigation out) only within
 * [TOUCH_WINDOW_MILLIS] of the viewer touching it, and one touch opens one URL. Shared by [FrameProtocol] (the
 * page's `ad.click` and `ad.open`) and [FrameNavigation] (a navigation out), so a tap spent on one is spent on
 * both.
 *
 * ```
 *   touch at t ──► a URL asked for at t + 0..1000 ms: opens, and the touch is spent
 *              ──► a second URL, or one after t + 1000 ms: refused
 * ```
 */
internal class TouchGate {
    private var lastTouchAt = NO_TOUCH

    /** The viewer touched the page at [now]; a URL may be opened for [TOUCH_WINDOW_MILLIS]. */
    fun noteTouch(now: Long) {
        lastTouchAt = now
    }

    /** One tap opens one URL: the first click, open or navigation it authorized spends it. */
    fun consume(now: Long): Boolean {
        if (now - lastTouchAt > TOUCH_WINDOW_MILLIS) return false
        lastTouchAt = NO_TOUCH
        return true
    }

    companion object {
        const val TOUCH_WINDOW_MILLIS = 1_000L

        /** No touch yet, or the last one spent: far enough back that no window reaches it, and no overflow. */
        private const val NO_TOUCH = Long.MIN_VALUE / 2
    }
}
