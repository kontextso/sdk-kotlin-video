package so.kontext.video.internal.playback

import android.os.Handler
import android.os.Looper
import so.kontext.video.VideoAdsPlayer
import so.kontext.video.internal.rendering.Tracker

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

        /** The player moved to another item or played this one to its end, or `track` was called again while an overlay was on screen. */
        object ContentChanged : Change()

        /** The position jumped (a seek): an overlay's count of video time goes on from the new position. */
        object Jumped : Change()

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
    private var listener: VideoAdsPlayer.Listener? = null
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

    /** The playback speed, when the player knows it. */
    val playbackSpeed: Float? get() = player?.let { runCatching { it.playbackSpeed }.getOrNull() }

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
        val listener = object : VideoAdsPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = deliver(Change.Playback(isPlaying), generation)
            override fun onSeek() = deliver(Change.Jumped, generation)
            override fun onContentChanged() = deliver(Change.ContentChanged, generation)
        }
        this.listener = listener
        // The player's code: a destroy() or another track() inside it wins, and this listener is dropped.
        runCatching { player.addListener(listener) }
        if (generation != this.generation) {
            runCatching { player.removeListener(listener) }
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
        if (player != null && listener != null) runCatching { player.removeListener(listener) }
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
