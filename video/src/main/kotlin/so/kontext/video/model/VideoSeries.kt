package so.kontext.video.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata of the series the video belongs to — for publishers whose context
 * lives at the series level (short drama: the episode has no synopsis of its
 * own). Everything is optional except the id; send what you have.
 *
 * @property description Synopsis — the main contextual signal when episodes carry none.
 * @property contentRating Content rating / brand-safety flag, e.g. `"18+"`. Constrains which campaigns may serve.
 * @property languages Languages the series is available in (BCP-47 / ISO 639 codes).
 */
@Serializable
public data class VideoSeries(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val keywords: List<String>? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val languages: List<String>? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)
