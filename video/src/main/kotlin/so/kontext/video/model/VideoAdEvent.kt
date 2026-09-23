package so.kontext.video.model

/**
 * Ad lifecycle events delivered via `VideoSessionOptions.onEvent` on the main
 * thread. Mirrors sdk-swift-video's `VideoAdEvent`.
 */
public sealed class VideoAdEvent {
    /**
     * A `load(formats)` finished: the server answered, and this is what is
     * ready — one result row per requested format. An empty `ads` with a
     * `noFillReason` is the no-fill case.
     */
    public data class Loaded(val data: LoadedData) : VideoAdEvent()

    /** An ad became visible to the user. */
    public data class Shown(val ad: AdData) : VideoAdEvent()

    /** The user tapped/clicked an ad. */
    public data class Clicked(val ad: AdData) : VideoAdEvent()

    /** The user dismissed the ad before it ended (the interstitial's Close chip). The ad is already hidden. */
    public data class Closed(val ad: AdData) : VideoAdEvent()

    /** A video ad (interstitial) played to the end. */
    public data class Completed(val ad: AdData) : VideoAdEvent()

    /** Something went wrong (network, rendering, …). */
    public data class Error(val error: ErrorData) : VideoAdEvent()

    /**
     * @property videoId The publisher's unique video ID the ad belongs to.
     * @property adId Unique per served ad, so two `Shown` events with different
     *   `adId`s are two different ads. Matches `Loaded.data.results[].ads[].id`.
     */
    public data class AdData(val format: VideoAdFormat, val videoId: String, val adId: String)

    public data class LoadedData(
        val videoId: String,
        /** One row per requested format. */
        val results: List<FormatResult>,
    ) {
        public data class FormatResult(
            val format: VideoAdFormat,
            /** The ads this load appended to the placement's queue, in order. Empty = no fill. */
            val ads: List<Ad>,
            /** Why the server served less than it could have (`no_ad_match`, `frequency_capped`, `content_unsafe`, …). */
            val noFillReason: String?,
        ) {
            /**
             * @property id Unique per served ad — matches the `adId` on later `Shown` / `Clicked` events and the placement's `pending` entries.
             * @property ecpm USD per 1,000 impressions — the same value as `VideoAd.ecpm` on the placement's `pending` entry.
             */
            public data class Ad(val id: String, val ecpm: Double)
        }
    }

    public data class ErrorData(val videoId: String?, val message: String)

    /** Stable string identifier of the event, for logs and analytics. */
    public val name: String
        get() = when (this) {
            is Loaded -> "ad.loaded"
            is Shown -> "ad.shown"
            is Clicked -> "ad.clicked"
            is Closed -> "ad.closed"
            is Completed -> "ad.completed"
            is Error -> "ad.error"
        }
}
