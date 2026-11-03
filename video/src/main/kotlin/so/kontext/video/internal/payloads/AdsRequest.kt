package so.kontext.video.internal.payloads

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.device.Consent
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.network.putIfNotNull
import so.kontext.video.internal.support.Environment
import java.util.UUID
import kotlin.math.roundToInt

/** The body of `POST /video/ads`: every format, for one video, for one `VideoAds` session. */
internal object AdsRequest {
    /** [held]: the ids the request lists, read after its waits. */
    class Built(val requestId: String, val body: JsonObject, val playheadMillis: Long, val held: List<String>)

    suspend fun make(
        context: Context,
        publisherToken: String,
        content: Content,
        labels: Map<String, String>,
        playheadMillis: Long,
        sessionId: String,
        held: () -> List<String>,
    ): Built {
        val requestId = UUID.randomUUID().toString()
        // The slow prerequisite first: the viewer may reset the ad id, or the consent and the user may change,
        // while it is read. What identifies the viewer and what they allow is read last, just before the body
        // (consent and the user inside it, with nothing suspending between).
        val userAgent = DeviceInfo.requestUserAgent(context)
        val ifa = Environment.advertisingId()
        // A usable CMP value wins per field; the publisher fills in the rest. COPPA comes only from the publisher.
        val regulatory = Consent.read(context, Sdk.regulatory)
        // A child's request carries no identifier for advertising: no advertising id, no user id or email.
        val isChild = regulatory?.coppa == true
        // The ads held now, after the waits: one shown, rejected or expired meanwhile is not listed.
        val heldIds = held()
        val body = buildJsonObject {
            put("requestId", requestId)
            put("publisherToken", publisherToken)
            put("installId", DeviceInfo.installId(context))
            (if (isChild) Sdk.user?.withoutIdentity else Sdk.user)?.let { put("user", user(it)) }
            putIfNotNull("variantId", Sdk.variantId)
            // Only an id the viewer allows: one with limited ad tracking is not read at all.
            ifa?.takeUnless { isChild }?.let {
                put(
                    "ifa",
                    buildJsonObject {
                        put("id", it)
                        put("type", "aaid")
                        put("limitAdTracking", false)
                    },
                )
            }
            regulatory?.let { put("regulatory", regulatory(it)) }
            put("sdk", SdkInfo.json())
            put("app", DeviceInfo.app(context))
            put(
                "device",
                buildJsonObject {
                    put("os", DeviceInfo.os(context))
                    put("hardware", DeviceInfo.hardware(context))
                    put("screen", DeviceInfo.screen(context))
                    put("power", DeviceInfo.power(context))
                    put("audio", DeviceInfo.audio(context))
                    put("network", buildJsonObject { putIfNotNull("userAgent", userAgent) })
                },
            )
            put("player", player(context, playheadMillis))
            put("video", content(content))
            if (labels.isNotEmpty()) put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
            put("sessionId", sessionId)
            put("held", JsonArray(heldIds.map(::JsonPrimitive)))
        }
        return Built(requestId, body, playheadMillis, heldIds)
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
