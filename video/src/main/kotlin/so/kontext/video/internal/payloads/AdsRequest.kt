package so.kontext.video.internal.payloads

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException
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
) {
    companion object {
        /**
         * The request, or `Request(MissingUser)` when no usable `KontextVideo.user` is set once the waits are over:
         * every request names the viewer.
         */
        suspend fun make(
            context: Context,
            publisherToken: String,
            content: Content,
            section: String?,
            variantId: String?,
            playheadMillis: Long,
            sessionId: String,
        ): AdsRequest {
            val requestId = UUID.randomUUID().toString()
            // The slow prerequisite first: the viewer may reset the ad id, or the consent and the user may change,
            // while it is read. What identifies the viewer and what they allow is read last, just before the body
            // (the stored consent off the main thread; the publisher's consent and the user after it, with nothing
            // suspending between them and the body).
            val userAgent = UserAgent.requestUserAgent(context)
            val network = DeviceInfo.networkFacts(context)
            val ifa = Environment.advertisingId()
            // A usable CMP value wins per field; the publisher fills in the rest. COPPA comes only from the publisher.
            val regulatory = Consent.read(context) { SdkState.current.regulatory }
            val user = SdkState.current.user.usable ?: throw VideoAdException.Request(VideoAdException.RequestFailure.MissingUser)
            val body = buildJsonObject {
                put("requestId", requestId)
                put("publisherToken", publisherToken)
                put("installId", DeviceInfo.installId(context))
                put("user", regulatory.userToSend(user).toJson())
                putIfNotNull("variantId", variantId.sendable)
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
                        put("network", DeviceInfo.network(userAgent, network))
                    },
                )
                put("player", player(context, playheadMillis))
                put("video", content.toJson())
                putIfNotNull("section", section.sendable)
                put("sessionId", sessionId)
            }
            return AdsRequest(requestId, body, playheadMillis)
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
