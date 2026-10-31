package so.kontext.video

import androidx.annotation.MainThread

/**
 * The content player, for a player that is not supported out of the box (Media3 is, in `so.kontext:video-media3`).
 * Give it to `VideoAds.track(player)`. The SDK reads it on the main thread and holds it until
 * `VideoAds.destroy()` or the next `track`; an adapter should hold your player weakly.
 */
public interface VideoAdsPlayer {
    /** Where the content is, in milliseconds of the episode. */
    @get:MainThread
    public val currentPositionMillis: Long

    /**
     * `false` when the viewer or your app paused the content, or it ended. Buffering and seeking count as
     * playing: the content is about to move on.
     */
    @get:MainThread
    public val isPlaying: Boolean

    /**
     * Optional: call [listener] on the main thread whenever [isPlaying] changes, for an immediate reaction.
     * Without it the SDK reads [isPlaying] four times a second while an overlay is on screen, and at every `show`.
     */
    @MainThread
    public fun addIsPlayingListener(listener: IsPlayingListener) {}

    /** Stops calling a listener given to [addIsPlayingListener]. */
    @MainThread
    public fun removeIsPlayingListener(listener: IsPlayingListener) {}

    /** Told whenever the player's `isPlaying` changes. */
    public fun interface IsPlayingListener {
        public fun onIsPlayingChanged(isPlaying: Boolean)
    }
}
