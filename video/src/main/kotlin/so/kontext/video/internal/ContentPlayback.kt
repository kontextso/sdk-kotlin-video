package so.kontext.video.internal

import android.os.Handler
import android.os.Looper
import so.kontext.video.VideoAdsPlayer
import kotlin.math.abs

/**
 * The content player of one `VideoAds`: its playhead for requests and beacons, and its playing state for an
 * overlay, which lives only while the content plays. Fed by a [VideoAdsPlayer]: its listener, or a read every
 * [tickIntervalMillis] while an overlay is on screen. [stop] drops the listener, the ticks and the player.
 * Every callback carries the binding it came from: one from a player tracked before is dropped.
 */
internal class ContentPlayback {
    sealed class Change {
        /** The playing state changed, with the value the player gave. */
        data class Playback(val isPlaying: Boolean?) : Change()

        /** `track` was called again while an overlay was on screen. */
        object ContentChanged : Change()

        /** [tickIntervalMillis] passed while ticking: an overlay counts its milliseconds of video. */
        object Tick : Change()
    }

    var onChange: ((Change) -> Unit)? = null

    /** Whether an overlay of the `VideoAds` is on screen now; set by the `VideoAds`. */
    var isOverlayShown: () -> Boolean = { false }

    /** `track` was called and [stop] was not since. */
    var isTracking = false
        private set

    private var player: VideoAdsPlayer? = null
    private var listener: VideoAdsPlayer.IsPlayingListener? = null
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            onChange?.invoke(Change.Tick)
            if (isTicking) handler.postDelayed(this, tickIntervalMillis)
        }
    }

    /** Whether the content plays. `true` when nothing was tracked: an overlay runs as it would. */
    val isPlaying: Boolean get() = if (!isTracking) true else player?.let { runCatching { it.isPlaying }.getOrDefault(false) } ?: false

    /** Content time in milliseconds, read now, or 0. Clamped to ten years. */
    val playheadMillis: Long
        get() = player?.let { runCatching { it.currentPositionMillis }.getOrNull() }?.coerceIn(0, Tracker.MAX_PLAYHEAD_MILLIS) ?: 0

    /** Ticks run while an overlay of this `VideoAds` is on screen, and only then. */
    var isTicking = false
        set(value) {
            if (field == value) return
            field = value
            handler.removeCallbacks(tickRunnable)
            if (value) handler.postDelayed(tickRunnable, tickIntervalMillis)
        }

    /** Replaces whatever was tracked. An overlay on screen followed the old player: once bound, it hears `ContentChanged`. */
    fun track(player: VideoAdsPlayer) {
        val expected = generation + 1
        stop()
        // Removing the old listener is app code: a `track` or `destroy()` there won, and this call stops.
        if (generation != expected) return
        // Asked of the ad on screen, not of the ticks: a `track` nested in that removal runs after `stop()`
        // turned them off, and must still hand the overlay over.
        val overlayWasShown = isOverlayShown()
        isTracking = true
        isTicking = overlayWasShown
        this.player = player
        val generation = generation
        val listener = VideoAdsPlayer.IsPlayingListener { playing -> deliver(Change.Playback(playing), generation) }
        this.listener = listener
        // The player's code: a destroy() or another track() inside it wins, and this listener is dropped.
        runCatching { player.addIsPlayingListener(listener) }
        if (generation != this.generation) {
            runCatching { player.removeIsPlayingListener(listener) }
            return
        }
        if (overlayWasShown) deliver(Change.ContentChanged, generation)
    }

    /** Nothing tracked any more: the listener and the ticks go, and the player is no longer read. */
    fun stop() {
        generation += 1
        val player = player
        val listener = listener
        this.player = null
        this.listener = null
        isTracking = false
        isTicking = false
        // Last, with every property settled: a `track` or `destroy()` in the removal finds a stopped playback.
        if (player != null && listener != null) runCatching { player.removeIsPlayingListener(listener) }
    }

    private fun deliver(change: Change, generation: Int) {
        if (generation != this.generation) return
        // A listener may be called off the main thread by a careless adapter: the change is handled on it.
        if (Looper.myLooper() == Looper.getMainLooper()) onChange?.invoke(change) else handler.post { deliver(change, generation) }
    }

    companion object {
        /** How often a tick comes while an overlay is on screen. Tests shorten it. */
        var tickIntervalMillis = 250L
    }
}

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
    fun advance(playheadMillis: Long, counting: Boolean): Boolean {
        val step = playheadMillis - last
        if (step != 0L) {
            val now = Environment.now()
            val real = now - lastMovedAt
            last = playheadMillis
            lastMovedAt = now
            if (counting && step > 0 && step <= abs(real) * MAX_RATE + SLACK_MILLIS) elapsedMillis += step
        }
        return elapsedMillis >= durationMillis
    }

    companion object {
        /** The speed allowed: 4× playback and a late sample still count. */
        const val MAX_RATE = 4.25

        /** What a step may exceed [MAX_RATE] by: the sampling's own jitter. */
        const val SLACK_MILLIS = 250L
    }
}
