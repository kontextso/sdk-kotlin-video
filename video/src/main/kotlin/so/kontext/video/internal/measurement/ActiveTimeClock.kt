package so.kontext.video.internal.measurement

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger

/**
 * A stopwatch that runs only while the viewer can see an overlay or pause ad.
 *
 * Why: an overlay's `duration` (say 10 s) must mean 10 s in front of the viewer. Without it, an app sent to the
 * background for a minute would come back to an overlay already gone, unseen; and `overlayViewDuration` would
 * report time nobody watched.
 *
 * How: it runs while both are true, and pauses, keeping its time, as soon as one is not:
 * - the window is visible: the app is in front, its Activity started (AdHostView.onWindowVisibility tells it);
 * - the ad is seen: some of it is on screen, not scrolled away (ViewabilityMeter.onSeen tells it).
 * It adds up the stretches: `accumulated` holds the finished ones, `since` when the current one began.
 *
 *     shown ─ 4 s ─ background ─ (paused) ─ back ─ 6 s ─ 10 s reached: onElapsed, the ad ends
 *
 * Used for: an overlay's duration (a timer waits out only the time still missing, then calls [onElapsed] once),
 * and the active time [stop] returns at the end, reported as `overlayViewDuration`. An overlay that follows a
 * tracked player counts its duration in video time instead (ContentProgress): its clock gets no duration.
 *
 * Not the 50 %-for-1-second viewability rule: that is ViewabilityMeter's (at least half on screen, without a
 * break). This clock counts any part seen, with breaks. It only counts and tells; the ad decides what an
 * elapsed duration means.
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
