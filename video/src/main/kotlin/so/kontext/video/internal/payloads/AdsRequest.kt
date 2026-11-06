package so.kontext.video.internal.payloads

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.device.Consent
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.device.UserAgent
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState
import java.util.UUID
import kotlin.math.roundToInt

/** The body of `POST /video/ads`: every format, for one video, for one `VideoAds` session. As [ConfigRequest]. */
internal class AdsRequest private constructor(
    /** A new UUID per request: it traces one request through the device log, the debug events, the error reports and the server's log. */
    val requestId: String,
    val body: JsonObject,
    /** The playhead the request carries. */
    val playheadMillis: Long,
    /** The ids the request lists, read after its waits. */
    val held: List<String>,
) {
    companion object {
        suspend fun make(
            context: Context,
            publisherToken: String,
            content: Content,
            labels: Map<String, String>,
            playheadMillis: Long,
            sessionId: String,
            held: () -> List<String>,
        ): AdsRequest {
            val requestId = UUID.randomUUID().toString()
            // The slow prerequisite first: the viewer may reset the ad id, or the consent and the user may change,
            // while it is read. What identifies the viewer and what they allow is read last, just before the body
            // (the stored consent off the main thread; the publisher's consent and the user after it, with nothing
            // suspending between them and the body).
            val userAgent = UserAgent.requestUserAgent(context)
            val ifa = Environment.advertisingId()
            // A usable CMP value wins per field; the publisher fills in the rest. COPPA comes only from the publisher.
            val regulatory = Consent.read(context) { SdkState.current.regulatory }
            // The ads held now, after the waits: one shown, rejected or expired meanwhile is not listed.
            val heldIds = held()
            val body = buildJsonObject {
                put("requestId", requestId)
                put("publisherToken", publisherToken)
                put("installId", DeviceInfo.installId(context))
                regulatory.userToSend(SdkState.current.user)?.let { put("user", it.toJson()) }
                putIfNotNull("variantId", SdkState.current.variantId)
                // Only an id the viewer allows: one with limited ad tracking is not read at all.
                regulatory.advertisingIdToSend(ifa)?.let {
                    put(
                        "ifa",
                        buildJsonObject {
                            put("id", it)
                            put("type", "aaid")
                            put("limitAdTracking", false)
                        },
                    )
                }
                regulatory?.let { put("regulatory", it.toJson()) }
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
                put("video", content.toJson())
                if (labels.isNotEmpty()) put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
                put("sessionId", sessionId)
                put("held", JsonArray(heldIds.map(::JsonPrimitive)))
            }
            return AdsRequest(requestId, body, playheadMillis, heldIds)
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
    }
}
