package so.kontext.video

import androidx.annotation.MainThread

/**
 * The ads of one video ("content"): one per episode, created when it opens. Creating it sends nothing.
 * Keep it while the episode is on screen and [destroy] it when the viewer leaves. Every call is main-thread.
 *
 * [labels] say where the ads play, such as the viewer's stage in the series or the player's layout
 * (`mapOf("stage" to "first_episode")`); every ads request of this `VideoAds` carries them, fixed for its
 * life. Keys of `a-z`, `0-9` and `_`, values not empty, both at most 64 bytes, at most 20 pairs; the rest is
 * dropped with a warning. They describe the screen, never the viewer: no personal data.
 */
public class VideoAds @JvmOverloads constructor(
    /** The video the ads are for. */
    public val content: Content,
    labels: Map<String, String> = emptyMap(),
) {
    /** [destroy] was called. Terminal. */
    public val isDestroyed: Boolean get() = TODO()

    /** The id of the last ads request, for support: quote it to Kontext when an ad or a no-fill looks wrong. */
    public val lastRequestId: String? get() = TODO()

    /**
     * The ad for [format]: a held one if still valid, else a fresh request. Throws [VideoAdException]
     * (`NoFill`, `Request`, `Disabled`, `Destroyed`, `NotInitialized`, `InitializationFailed`).
     */
    @MainThread
    public suspend fun format(format: AdFormat): VideoAd = TODO()

    /** [format] for callers without coroutines: [callback] gets the ad, or the exception, on the main thread. */
    @MainThread
    public fun format(format: AdFormat, callback: VideoAdsCallback<VideoAd>): Unit = TODO()

    /**
     * The player this episode plays in. The SDK reads its time for requests and events, and follows its
     * playback for an overlay: an overlay shows over playing content only, and ends with `INTERRUPTED` when
     * the content pauses or ends. Held until [destroy] or the next `track`. Call it when this episode starts
     * playing in the player.
     */
    @MainThread
    public fun track(player: VideoAdsPlayer): Unit = TODO()

    /**
     * Releases the video's ads. Every held ad is dropped and the advertiser told it was not used; the ad on
     * screen ends with `DESTROYED`; a `format()` or `load()` in flight throws `Destroyed`. Idempotent.
     */
    @MainThread
    public fun destroy(): Unit = TODO()

    override fun toString(): String = "VideoAds(content=${content.id}, destroyed=$isDestroyed)"
}
