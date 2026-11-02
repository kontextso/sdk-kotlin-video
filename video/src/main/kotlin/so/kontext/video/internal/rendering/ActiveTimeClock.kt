package so.kontext.video.internal.rendering

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger

/**
 * The time an overlay or pause ad is seen: its window visible (the Activity started) and some of it on screen,
 * as the viewability meter measures. `overlayViewDuration` reports it, and an overlay's duration runs on it, so
 * both freeze while the app is in the background or the ad is scrolled out of view, and resume on return
 * (another Activity of the app covering it ends the ad instead). It only counts and tells; the ad decides what
 * an elapsed duration means. The host view tells it when its window shows or hides, the meter when the ad is
 * seen.
 */
internal class ActiveTimeClock(
    private val durationMillis: Long?,
    private val adId: String,
    private val onElapsed: () -> Unit,
) {
    private var since: Long? = null
    private var accumulated = 0L
    private var started = false
    private var windowVisible = false
    private var seen = true
    private var stopped = false
    private var expired = false
    private var timer: Job? = null

    /** An active stretch is running. */
    val isCounting: Boolean get() = since != null

    /** Called when the window came back after the clock started. */
    var onActivate: (() -> Unit)? = null

    /** Milliseconds on screen while visible, the current stretch included. */
    val elapsedMillis: Long get() = accumulated + (since?.let { Environment.now() - it } ?: 0)

    /** The ad is on screen; counts from now when its window is visible and the ad [seen]. */
    fun start(visible: Boolean, seen: Boolean = true) {
        if (stopped || started) return
        started = true
        windowVisible = visible
        this.seen = seen
        update()
    }

    /** The host's window was shown or hidden. */
    fun windowVisibilityChanged(visible: Boolean) {
        if (!started || stopped) return
        val returned = visible && !windowVisible
        windowVisible = visible
        update()
        if (returned) onActivate?.invoke()
    }

    /** The meter found the ad seen, or not seen at all. */
    fun seenChanged(seen: Boolean) {
        if (!started || stopped) return
        this.seen = seen
        update()
    }

    private fun update() {
        if (windowVisible && seen) resume() else pause()
    }

    /** The ad ended: the clock freezes for good and returns the active time it counted. */
    fun stop(): Long {
        if (!stopped) {
            pause()
            stopped = true
        }
        return accumulated
    }

    private fun pause() {
        since?.let {
            accumulated += Environment.now() - it
            since = null
        }
        timer?.cancel()
        timer = null
        if (!stopped) Logger.debug { "ad ${adId.take(8)}: clock frozen at $accumulated ms on screen" }
    }

    private fun resume() {
        if (stopped || since != null) return
        since = Environment.now()
        startTimer()
    }

    private fun startTimer() {
        val duration = durationMillis ?: return
        if (timer != null || expired) return
        val remaining = (duration - elapsedMillis).coerceIn(0, 86_400_000)
        timer = Environment.scope.launch {
            delay(remaining)
            if (stopped || expired) return@launch
            expired = true
            onElapsed()
        }
    }
}
