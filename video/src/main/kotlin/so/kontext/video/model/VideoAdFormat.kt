package so.kontext.video.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ad formats supported by the Video SDK. Wire values are the ad server's `AdFormat` enum names. */
@Serializable
public enum class VideoAdFormat(public val wireValue: String) {
    /** Image banner over part of the paused player (IAB Pause, `pos = 8`). */
    @SerialName("INSTREAM_IMAGE_PAUSE")
    PAUSE("INSTREAM_IMAGE_PAUSE"),

    /** Full-screen video the SDK presents at a moment the app chooses; closable once the server's `skip_after_ms` has passed. */
    @SerialName("INSTREAM_VIDEO_INTERSTITIAL")
    INTERSTITIAL("INSTREAM_VIDEO_INTERSTITIAL"),

    /** Image banner while the episode plays, N per video, each with a server-derived window (IAB Overlay). */
    @SerialName("INSTREAM_IMAGE_OVERLAY")
    OVERLAY("INSTREAM_IMAGE_OVERLAY"),
}
