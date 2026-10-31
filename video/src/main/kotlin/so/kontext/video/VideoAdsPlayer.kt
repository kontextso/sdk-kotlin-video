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
     * The playback speed (1 is normal), when the player knows it: an overlay's duration counts milliseconds
     * of video, and the speed tells fast playback from a seek. `null` (the default) allows up to about 4×.
     */
    @get:MainThread
    public val playbackSpeed: Float? get() = null

    /**
     * Optional: tell [listener], on the main thread, what the player does, for an immediate reaction. Without
     * it the SDK reads [isPlaying] four times a second while an overlay is on screen, and at every `show`, and
     * estimates seeks from the position.
     */
    @MainThread
    public fun addListener(listener: Listener) {}

    /** Stops telling a listener given to [addListener]. */
    @MainThread
    public fun removeListener(listener: Listener) {}

    /** What the player tells the SDK. Call each as it happens, without merging: a pause and a resume are two calls. */
    public fun interface Listener {
        /** [isPlaying] changed. */
        public fun onIsPlayingChanged(isPlaying: Boolean)

        /** The position jumped (a seek): an overlay's count of video time goes on from the new position. */
        public fun onSeek() {}

        /** The player moved to another item, or played this one to its end: an overlay over it ends. */
        public fun onContentChanged() {}
    }
}
