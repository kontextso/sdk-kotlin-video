package so.kontext.video.internal.playback

import so.kontext.video.internal.support.Environment
import kotlin.math.abs

/**
 * An overlay's duration in milliseconds of video: the playhead's progress while the overlay is on screen and
 * its window visible ([resync] when it comes back, so time hidden counts nothing). A step backwards, or one
 * faster than [MAX_RATE] times the real time since the playhead last moved, is a seek or a loop: it counts
 * nothing, and counting goes on from the new position.
 */
internal class ContentProgress(val durationMillis: Long, playheadMillis: Long) {
    var elapsedMillis = 0L
        private set
    private var last = playheadMillis
    private var lastMovedAt = Environment.now()

    fun resync(playheadMillis: Long) {
        last = playheadMillis
        lastMovedAt = Environment.now()
    }

    /** Takes the playhead now; counts the step when [counting] and it looks like playback. `true` once the duration is reached. */
    fun advance(playheadMillis: Long, counting: Boolean, speed: Float? = null): Boolean {
        val step = playheadMillis - last
        if (step != 0L) {
            val now = Environment.now()
            val real = now - lastMovedAt
            last = playheadMillis
            lastMovedAt = now
            val allowed = speed?.let { maxOf(abs(it) * RATE_MARGIN, 1.0) } ?: MAX_RATE
            if (counting && step > 0 && step <= abs(real) * allowed + SLACK_MILLIS) elapsedMillis += step
        }
        return elapsedMillis >= durationMillis
    }

    companion object {
        /** The speed allowed when the player does not tell its own: 4× playback and a late sample still count. */
        const val MAX_RATE = 4.25

        /** Room above a known speed for a late sample. */
        const val RATE_MARGIN = 1.25

        /** What a step may exceed [MAX_RATE] by: the sampling's own jitter. */
        const val SLACK_MILLIS = 250L
    }
}
