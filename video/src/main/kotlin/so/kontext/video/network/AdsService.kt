package so.kontext.video.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import so.kontext.video.SDKInfo
import so.kontext.video.internal.log
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoSeries

/**
 * `POST /instream/preload` — one call per video, returns every ad prepared
 * for it. Wire format is snake_case, the ad server's instream vocabulary;
 * during development the mock server implements it.
 */
internal class AdsService(private val adServerUrl: String) {
    @Serializable
    class PreloadRequest(
        @SerialName("publisher_token") val publisherToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("user_email") val userEmail: String? = null,
        @SerialName("session_id") val sessionId: String? = null,
        @SerialName("install_id") val installId: String,
        /** The publisher's unique video id. */
        val id: String,
        val title: String? = null,
        val description: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val language: String? = null,
        @SerialName("episode_number") val episodeNumber: Int? = null,
        val series: VideoSeries? = null,
        val formats: List<VideoAdFormat>,
        val context: Context? = null,
        val sdk: SDKInfo.Dto,
    ) {
        @Serializable
        class Context(
            @SerialName("player_width") val playerWidth: Int,
            @SerialName("player_height") val playerHeight: Int,
        )
    }

    @Serializable
    class FormatResult(val format: String, @SerialName("no_fill_reason") val noFillReason: String? = null)

    class PreloadResponse(
        val sessionId: String?,
        val ads: List<VideoAd>,
        val results: List<FormatResult>?,
        val noFillReason: String?,
    )

    suspend fun preload(request: PreloadRequest, timeoutMs: Long): PreloadResponse {
        val response = HttpClient.postJson("$adServerUrl/instream/preload", json.encodeToString(request), timeoutMs)
        if (response.statusCode != 200) throw HttpException(response.statusCode)
        return decode(response.body)
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        /** Lossy: one malformed ad must not throw away the rest of the fill. */
        fun decode(body: String): PreloadResponse {
            val root = json.parseToJsonElement(body).jsonObject
            val ads = (root["ads"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching { json.decodeFromJsonElement(VideoAd.serializer(), element) }
                    .onFailure { log("skipping malformed ad: ${it.message}") }
                    .getOrNull()
            }
            val results = (root["results"] as? JsonArray)?.let { json.decodeFromJsonElement(ResultsSerializer, it) }
            return PreloadResponse(
                sessionId = root.string("session_id"),
                ads = ads,
                results = results,
                noFillReason = root.string("no_fill_reason"),
            )
        }

        private val ResultsSerializer = kotlinx.serialization.builtins.ListSerializer(FormatResult.serializer())

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull
    }
}
