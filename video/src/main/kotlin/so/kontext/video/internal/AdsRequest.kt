package so.kontext.video.internal

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.Content
import so.kontext.video.Regulatory
import so.kontext.video.User
import java.util.UUID
import kotlin.math.roundToInt

/** The body of `POST /video/ads`: every format, for one video, for one `VideoAds` session. */
internal object AdsRequest {
    class Built(val requestId: String, val body: JsonObject, val playheadMillis: Long)

    suspend fun make(
        context: Context,
        publisherToken: String,
        content: Content,
        labels: Map<String, String>,
        playheadMillis: Long,
        sessionId: String,
        held: List<String>,
    ): Built {
        val requestId = UUID.randomUUID().toString()
        val ifa = Environment.advertisingId()
        val userAgent = Environment.userAgent()
        val body = buildJsonObject {
            put("requestId", requestId)
            put("publisherToken", publisherToken)
            put("installId", DeviceFacts.installId(context))
            Sdk.user?.let { put("user", user(it)) }
            putIfNotNull("variantId", Sdk.variantId)
            // Only an id the viewer allows: one with limited ad tracking is not read at all.
            ifa?.let {
                put(
                    "ifa",
                    buildJsonObject {
                        put("id", it)
                        put("type", "gaid")
                        put("limitAdTracking", false)
                    },
                )
            }
            // A usable CMP value wins per field; the publisher fills in the rest. COPPA comes only from the publisher.
            Consent.read(context, Sdk.regulatory)?.let { put("regulatory", regulatory(it)) }
            put("sdk", DeviceFacts.sdk())
            put("app", DeviceFacts.app(context))
            put(
                "device",
                buildJsonObject {
                    put("os", DeviceFacts.os(context))
                    put("hardware", DeviceFacts.hardware(context))
                    put("screen", DeviceFacts.screen(context))
                    put("power", DeviceFacts.power(context))
                    put("audio", DeviceFacts.audio(context))
                    put("network", buildJsonObject { putIfNotNull("userAgent", userAgent) })
                },
            )
            put("player", player(context, playheadMillis))
            put("video", content(content))
            if (labels.isNotEmpty()) put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
            put("sessionId", sessionId)
            put("held", JsonArray(held.map(::JsonPrimitive)))
        }
        return Built(requestId, body, playheadMillis)
    }

    /** The SDK assumes a full-screen player: the screen in its current orientation, in dp, and the playhead. */
    fun player(context: Context, playheadMillis: Long): JsonObject {
        val metrics = context.resources.displayMetrics
        return buildJsonObject {
            put("width", (metrics.widthPixels / metrics.density).roundToInt())
            put("height", (metrics.heightPixels / metrics.density).roundToInt())
            put("fullscreen", true)
            put("playhead", playheadMillis)
        }
    }

    /** The wire form: absent fields left out, as are numbers the server's schema would refuse. */
    fun content(content: Content): JsonObject = buildJsonObject {
        put("id", content.id)
        put("language", content.language)
        putIfNotNull("mediaRating", content.mediaRating?.name?.lowercase())
        putIfNotNull("url", content.url)
        putIfNotNull("title", content.title)
        putIfNotNull("categories", content.categories)
        putIfNotNull("description", content.description)
        putIfNotNull("duration", content.durationMillis?.takeIf { it >= 1 })
        putIfNotNull("episodeNumber", content.episodeNumber?.takeIf { it >= 1 })
        putIfNotNull("keywords", content.keywords)
        putIfNotNull("genre", content.genre)
        content.series?.takeIf { it.id.isNotEmpty() }?.let { series ->
            put(
                "series",
                buildJsonObject {
                    put("id", series.id)
                    putIfNotNull("title", series.title)
                    putIfNotNull("description", series.description)
                    putIfNotNull("episodeCount", series.episodeCount?.takeIf { it >= 1 })
                },
            )
        }
    }

    /** Empty strings are left out: an empty id, address or hash identifies nobody. */
    fun user(user: User): JsonObject = buildJsonObject {
        putIfNotNull("id", user.id?.takeIf { it.isNotEmpty() })
        putIfNotNull("email", user.email?.takeIf { it.isNotEmpty() })
        putIfNotNull("emailHash", user.emailHash?.takeIf { it.isNotEmpty() })
        putIfNotNull("keywords", user.keywords)
    }

    fun regulatory(regulatory: Regulatory): JsonObject = buildJsonObject {
        putIfNotNull("gdpr", regulatory.gdpr)
        putIfNotNull("gdprConsent", regulatory.gdprConsent)
        putIfNotNull("coppa", regulatory.coppa)
        putIfNotNull("gpp", regulatory.gpp)
        putIfNotNull("gppSid", regulatory.gppSid)
        putIfNotNull("usPrivacy", regulatory.usPrivacy)
    }
}
