package so.kontext.video.internal.content

import android.os.Handler
import android.os.Looper
import so.kontext.video.ContentPlayer
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.measurement.TrackingMacros

/**
 * The content player of one `VideoAds`: its playhead for requests and beacons, and its playing state for an
 * overlay, which lives only while the content plays, and a pause ad, which lives only while it is paused. Fed
 * by a [ContentPlayer]: its listener, or a read every [tickIntervalMillis] while an overlay or pause ad is on
 * screen. [stop] drops the listener, the ticks and the player.
 * Every callback carries the binding it came from: one from a player tracked before is dropped. A player
 * belongs to one playback at a time: tracked by another, it is let go of here ([playsAnotherEpisode]).
 */
internal class ContentPlayback {
    sealed class Change {
        /**
         * The playing state changed, with the value the player gave. Nullable for parity with iOS, where the
         * player may not say; the Kotlin listener always passes a value.
         */
        data class PlayingChanged(val isPlaying: Boolean?) : Change()

        /** The player moved to another item or played this one to its end, or `track` was called again while an ad was on screen. */
        object ContentChanged : Change()

        /** The position jumped (a seek): an overlay's count of video time goes on from the new position. */
        object Seeked : Change()

        /** [tickIntervalMillis] passed while ticking: an overlay counts its milliseconds of video. */
        object Tick : Change()
    }

    var onChange: ((Change) -> Unit)? = null

    /** Whether an overlay or pause ad of the `VideoAds` is on screen now; set by the `VideoAds`. */
    var isInlineAdShown: () -> Boolean = { false }

    /** `track` was called and [stop] was not since. */
    var isTracking = false
        private set

    /** A player was tracked before: the first `track` hands no overlay over, it followed nothing. */
    private var hasTracked = false

    private var player: ContentPlayer? = null

    /**
     * The player's item at `track`, or the first it reported after: this episode. Held, not weak: a player may
     * report a value it makes on each read, compared by `equals`.
     */
    private var episodeItem: Any? = null
    private var hasEpisodeItem = false

    /** Another `VideoAds` tracks this one's player now: this episode no longer plays in it, until `track` again. */
    var hasLostPlayer = false
        private set

    /**
     * Whether the tracked content is no longer this `VideoAds`'s episode: another `VideoAds` took its player,
     * or the player moved to another item than this episode's. An overlay or pause ad shown now would be
     * counted for this episode over another one. Without a player, or for a player that reports no item,
     * nothing can tell: `false`.
     */
    val playsAnotherEpisode: Boolean
        get() {
            if (hasLostPlayer) return true
            val player = player ?: return false
            adoptEpisodeItem()
            if (!hasEpisodeItem) return false
            return runCatching { player.currentItem != episodeItem }.getOrDefault(false)
        }
    private var listener: ContentPlayer.Listener? = null
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            adoptEpisodeItem()
            onChange?.invoke(Change.Tick)
            // The tick's own work may have stopped and restarted the ticks (an overlay ended, its onAdEnded
            // showed the next one), which posted a tick already: exactly one stays pending.
            handler.removeCallbacks(this)
            if (isTicking) handler.postDelayed(this, tickIntervalMillis)
        }
    }

    /** Whether the content plays. `true` when nothing was tracked: an overlay runs as it would. */
    val isPlaying: Boolean get() = if (!isTracking) true else player?.let { runCatching { it.isPlaying }.getOrDefault(false) } ?: false

    /** The playback speed, when the player knows it. */
    val playbackSpeed: Float? get() = player?.let { runCatching { it.playbackSpeed }.getOrNull() }

    /** Content time in milliseconds, read now, or 0. Clamped to ten years. */
    val playheadMillis: Long
        get() = player?.let { runCatching { it.currentPositionMillis }.getOrNull() }?.coerceIn(0, TrackingMacros.MAX_PLAYHEAD_MILLIS) ?: 0

    /** Ticks run while an overlay or pause ad of this `VideoAds` is on screen with a player tracked, and only then. */
    var isTicking = false
        set(value) {
            if (field == value) return
            field = value
            handler.removeCallbacks(tickRunnable)
            if (value) handler.postDelayed(tickRunnable, tickIntervalMillis)
        }

    /**
     * Replaces whatever was tracked. An overlay on screen followed the old player: once bound, it hears
     * `ContentChanged`. One shown with no player tracked followed nothing, and goes on.
     */
    fun track(player: ContentPlayer) {
        val expected = generation + 1
        // Read before `stop()`, and set before it: a `track` nested in the old listener's removal is a change of
        // player too.
        val wasTracking = hasTracked
        hasTracked = true
        stop()
        // Removing the old listener is app code: a `track` or `destroy()` there won, and this call stops.
        if (generation != expected) return
        // Asked of the ad on screen, not of the ticks: a `track` nested in that removal runs after `stop()`
        // turned them off, and must still hand the overlay over.
        val inlineAdWasShown = isInlineAdShown()
        isTracking = true
        isTicking = inlineAdWasShown
        this.player = player
        val generation = generation
        val listener = object : ContentPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = deliver(Change.PlayingChanged(isPlaying), generation)
            override fun onSeek() = deliver(Change.Seeked, generation)
            override fun onContentChanged() = deliver(Change.ContentChanged, generation)
        }
        this.listener = listener
        // The player's code: a destroy() or another track() inside it wins, and this listener is dropped.
        runCatching { player.addListener(listener) }
        if (generation != this.generation) {
            runCatching { player.removeListener(listener) }
            return
        }
        adoptEpisodeItem()
        if (inlineAdWasShown && wasTracking) deliver(Change.ContentChanged, generation)
        // The overlay's `onAdEnded` there is app code: a `track` or `destroy()` in it won.
        if (generation != this.generation) return
        PlayerOwnership.claim(this, player)
    }

    /** Nothing tracked any more: the listener and the ticks go, and the player is no longer read. */
    fun stop() {
        generation += 1
        val player = player
        val listener = listener
        this.player = null
        this.listener = null
        episodeItem = null
        hasEpisodeItem = false
        hasLostPlayer = false
        PlayerOwnership.release(this)
        isTracking = false
        isTicking = false
        // Last, with every property settled: a `track` or `destroy()` in the removal finds a stopped playback.
        if (player != null && listener != null) runCatching { player.removeListener(listener) }
    }

    /**
     * Another `VideoAds` took the player. An overlay on screen hears `ContentChanged` first, as for another
     * item, and ends. Its `onAdEnded` is app code: a `track` there wins.
     */
    fun losePlayer() {
        val generation = generation
        if (isInlineAdShown()) deliver(Change.ContentChanged, generation)
        if (generation != this.generation) return
        Logger.info { "the player is tracked by another VideoAds now; this one no longer tracks it" }
        stop()
        hasLostPlayer = true
    }

    /** The first item seen from `track` on is this episode's: one there at `track`, or the first the player reported. */
    private fun adoptEpisodeItem() {
        if (hasEpisodeItem) return
        val item = player?.let { runCatching { it.currentItem }.getOrNull() } ?: return
        episodeItem = item
        hasEpisodeItem = true
    }

    private fun deliver(change: Change, generation: Int) {
        if (generation != this.generation) return
        if (Looper.myLooper() == Looper.getMainLooper()) adoptEpisodeItem()
        // A listener may be called off the main thread by a careless adapter: the change is handled on it.
        if (Looper.myLooper() == Looper.getMainLooper()) onChange?.invoke(change) else handler.post { deliver(change, generation) }
    }

    companion object {
        /** How often a tick comes while an overlay or pause ad is on screen. Tests shorten it. */
        var tickIntervalMillis = 250L
    }
}
