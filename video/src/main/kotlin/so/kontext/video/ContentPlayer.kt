package so.kontext.video

import androidx.annotation.MainThread

/**
 * The content player, for a player that is not supported out of the box (Media3, TTSDK and the Tencent Cloud Player
 * SDK are, in `so.kontext:video-media3`, `so.kontext:video-ttsdk` and `so.kontext:video-txplayer`).
 * Give it to `VideoAds.track(player)`. The SDK reads it on the main thread and holds it until
 * `VideoAds.destroy()` or the next `track`; an adapter should hold your player weakly.
 */
public interface ContentPlayer {
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
     * of video, and the speed tells fast playback from a seek. `null` (the default) allows up to about 4×; a value
     * that is not finite reads as `null`.
     */
    @get:MainThread
    public val playbackSpeed: Float? get() = null

    /**
     * Optional: what the player plays now, any value that stands for this play of the episode (your item, its
     * id), compared with `equals`; `null` (the default) when the player cannot tell, now or ever. Show an
     * episode's ads only in that episode: once it differs from the value at `track` (or, with `null` then, the
     * first one it gave), an overlay or pause ad of that `VideoAds` throws `Invalidated(ContentChanged)` at
     * `show`, and one on screen ends (`INTERRUPTED`) at the SDK's next read. A `null` read is never a change.
     * Give a new value for another episode, and for a loop's next play of the same one. Without it only another
     * `VideoAds` tracking the same player is checked.
     */
    @get:MainThread
    public val currentItem: Any? get() = null

    /**
     * Optional: tell [listener], on the main thread, what the player does, for an immediate reaction. Without
     * it the SDK reads [isPlaying] four times a second while an overlay or pause ad is on screen, and at every `show`, and
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
