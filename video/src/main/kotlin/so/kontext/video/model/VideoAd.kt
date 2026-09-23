package so.kontext.video.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One ad returned by `/instream/preload`, ready to render. Same shape as the
 * ad server's `InstreamOverlayAdJson` plus the trigger, placement and
 * playback blocks. Times are milliseconds, like the wire.
 *
 * @property adId Unique per served ad — the wire's `bid_id`. Matches the `adId` on every later event for this ad.
 * @property creativeId Stable id of the creative behind the ad, when the server sends one.
 * @property ecpm What this ad pays, in USD per 1,000 impressions. Known before the ad is shown, so the app can rank it against its other demand.
 */
@Serializable
public data class VideoAd(
    val format: VideoAdFormat,
    @SerialName("bid_id") val adId: String,
    @SerialName("creative_id") val creativeId: String? = null,
    val ecpm: Double,
    val trigger: Trigger,
    val placement: Placement? = null,
    val playback: Playback? = null,
    val creative: VideoAdCreative,
    val tracking: VideoAdTracking,
) {
    /**
     * @property kind `pause`, `range` (a window in content time), or `manual`
     *   (the app decides the moment — every interstitial, and overlays when the
     *   publisher shared no content to derive windows from).
     * @property fromMs Window start in content time, `range` triggers only.
     * @property toMs Window end in content time, `range` triggers only.
     * @property delayMs Optional wait after the trigger before showing. Absent
     *   or 0 = show immediately.
     */
    @Serializable
    public data class Trigger(
        val kind: String,
        @SerialName("from_ms") val fromMs: Long? = null,
        @SerialName("to_ms") val toMs: Long? = null,
        @SerialName("delay_ms") val delayMs: Long? = null,
    )

    /** @property position Server's preferred edge for a banner (`top` / `bottom`); the app may override. */
    @Serializable
    public data class Placement(val position: String? = null)

    /**
     * Video formats only.
     * @property skipAfterMs The ad may be closed after this long. `null` = not closable; it ends only when the video completes.
     */
    @Serializable
    public data class Playback(
        @SerialName("skip_after_ms") val skipAfterMs: Long? = null,
        val muted: Boolean? = null,
    )
}

/**
 * Render-ready creative. Image formats carry [image]; video formats carry
 * [video]. Copy is written asynchronously on the server, so [headline] /
 * [body] may be absent.
 */
@Serializable
public data class VideoAdCreative(
    val image: Image? = null,
    val video: Video? = null,
    val headline: String? = null,
    val body: String? = null,
    val cta: Cta? = null,
    val advertiser: Advertiser? = null,
) {
    @Serializable
    public data class Image(val url: String, val w: Int, val h: Int)

    @Serializable
    public data class Video(
        val url: String,
        @SerialName("mime_type") val mimeType: String? = null,
        val w: Int,
        val h: Int,
        @SerialName("duration_ms") val durationMs: Long? = null,
    )

    /** @property url `/impression/{bid}/redirect` — records the click, then forwards. */
    @Serializable
    public data class Cta(val text: String, val mode: String, val url: String)

    @Serializable
    public data class Advertiser(val name: String, @SerialName("logo_url") val logoUrl: String? = null)
}

/**
 * The ad server's `TrackingUrls` — one URL per event, fired by the SDK.
 * `[CACHEBUSTING]` is substituted per fire; each URL fires at most once.
 *
 * @property clickThrough Navigation target, not a pixel: opens the advertiser via the server.
 * @property video VAST linear events, video formats only (`/impression/{bid}/vast?event=`).
 */
@Serializable
public data class VideoAdTracking(
    val impression: String,
    val viewable: String,
    @SerialName("not_viewable") val notViewable: String,
    @SerialName("view_undetermined") val viewUndetermined: String,
    @SerialName("creative_view") val creativeView: String,
    val close: String,
    @SerialName("overlay_view_duration") val overlayViewDuration: String,
    @SerialName("click_through") val clickThrough: String,
    @SerialName("click_tracking") val clickTracking: String,
    val video: Video? = null,
) {
    @Serializable
    public data class Video(
        val start: String,
        @SerialName("first_quartile") val firstQuartile: String,
        val midpoint: String,
        @SerialName("third_quartile") val thirdQuartile: String,
        val complete: String,
        val skip: String,
        val mute: String? = null,
        val unmute: String? = null,
    )
}
