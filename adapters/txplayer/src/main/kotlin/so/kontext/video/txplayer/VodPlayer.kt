package so.kontext.video.txplayer

import so.kontext.video.ContentPlayer

/**
 * A `TXVodPlayer` as the SDK's [ContentPlayer].
 *
 * Playing is the player's own `isPlaying()`: true from the start of playback until a pause, the end or a stop, and
 * still true while it stalls to load, so buffering counts as playing, as the SDK's contract says.
 *
 * A `TXVodPlayer` has one listener slot, the app's: the app hands its play events on ([playEvent]), and they tell
 * the SDK at once of an end, a loop's next round and a seek. A pause has no event: the SDK reads [isPlaying] four
 * times a second while an ad is on screen, and at `show`, which is how it sees one, with or without the events.
 *
 * | Event                | Tells the SDK                                       |
 * |----------------------|-----------------------------------------------------|
 * | 2004 PLAY_BEGIN      | `onIsPlayingChanged(isPlaying())`                   |
 * | 2014 LOADING_END     | `onIsPlayingChanged(isPlaying())`                   |
 * | 2006 PLAY_END        | another play: `onContentChanged`, then not playing  |
 * | 6001 LOOP_ONCE_...   | another play: `onContentChanged`                    |
 * | 2019 SEEK_COMPLETE   | `onSeek`                                            |
 * | an error (< 0)       | `onIsPlayingChanged(isPlaying())`                   |
 */
internal class VodPlayer(private val vod: Vod) : ContentPlayer {
    private val listeners = mutableListOf<ContentPlayer.Listener>()

    /** Ends of the video the app handed on, a loop's rounds included: a replay is another play. */
    private var plays = 0

    /** The app's playback speed, for the current binding only; dropped with the last listener. */
    var speed: (() -> Float)? = null

    /** The app's item (its episode), for the current binding only; dropped with the last listener. */
    var item: (() -> Any?)? = null

    /** Someone follows the player through this adapter now. */
    val isFollowed: Boolean get() = listeners.isNotEmpty()

    /** How many `track` calls have bound this adapter: the latest one's speed and item are the ones that count. */
    private var bindings = 0

    /** How many `track` calls through [bind] are running now, nested ones included. */
    private var tracking = 0

    override val currentPositionMillis: Long
        get() {
            if (vod.isGone) return 0
            val seconds = vod.positionSeconds
            return if (seconds.isFinite() && seconds > 0) (seconds * MILLIS_PER_SECOND).toLong() else 0
        }

    override val isPlaying: Boolean get() = !vod.isGone && vod.isPlaying

    override val playbackSpeed: Float? get() = speed?.let { runCatching { it() }.getOrNull() }

    /**
     * The app's item and which play of it. Without an item from the app, the plays alone: the player itself is the
     * episode, and an end or a loop's next round is another play. The app's `null` is "cannot tell now".
     */
    override val currentItem: Any?
        get() {
            if (vod.isGone) return null
            val item = item ?: return Play(null, plays)
            return runCatching { item() }.getOrNull()?.let { Play(it, plays) }
        }

    /** One play of an item. */
    private data class Play(val item: Any?, val count: Int)

    /**
     * Runs [track] (the SDK's, with this adapter) with [currentItem] and [playbackSpeed] as this binding's. They are
     * set before it, since the SDK reads the item there as the episode's, and kept through it: the SDK's own
     * `track` lets go of the adapter first when the same `VideoAds` tracks the same player again. A `track` of the
     * same player nested in it (an ad's `onAdEnded` run by the handover, tracking it in a third `VideoAds`) made a
     * newer binding, which wins: its own stay, not this outer call's.
     */
    fun bind(currentItem: (() -> Any?)?, playbackSpeed: (() -> Float)?, track: (VodPlayer) -> Unit) {
        val binding = ++bindings
        item = currentItem
        speed = playbackSpeed
        tracking += 1
        try {
            track(this)
        } finally {
            tracking -= 1
        }
        // Nobody follows it after all (the SDK refused): nothing of the app's is kept by the shared adapter.
        if (binding == bindings && !isFollowed) dropBinding()
    }

    override fun addListener(listener: ContentPlayer.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: ContentPlayer.Listener) {
        listeners -= listener
        if (listeners.isEmpty() && tracking == 0) dropBinding()
    }

    private fun dropBinding() {
        speed = null
        item = null
    }

    /** One of the app's play events, on the main thread. Nobody follows the player: nobody hears it. */
    fun playEvent(event: Int) {
        when {
            event == PlayEvent.PLAY_BEGIN || event == PlayEvent.LOADING_END || event < 0 -> playingChanged()
            event == PlayEvent.PLAY_END -> {
                plays += 1
                listeners.toList().forEach { it.onContentChanged() }
                listeners.toList().forEach { it.onIsPlayingChanged(false) }
            }
            event == PlayEvent.LOOP_ONCE_COMPLETE -> {
                plays += 1
                listeners.toList().forEach { it.onContentChanged() }
            }
            event == PlayEvent.SEEK_COMPLETE -> listeners.toList().forEach { it.onSeek() }
        }
    }

    // The player's state now, not the event's: the app's own handler of the event may have paused it already (a
    // neighbour page in a feed, prepared ahead, that begins and is paused at once).
    private fun playingChanged() {
        val isPlaying = isPlaying
        listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
