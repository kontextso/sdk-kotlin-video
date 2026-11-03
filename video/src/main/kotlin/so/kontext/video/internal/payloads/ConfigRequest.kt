package so.kontext.video.internal.payloads

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.internal.device.DeviceInfo
import java.util.UUID

/**
 * The body of `POST /config`. As the Swift SDK's `ConfigRequest`, less `attribution` (SKAdNetwork and
 * AdAttributionKit ids, iOS only).
 */
internal class ConfigRequest private constructor(
    /**
     * A new UUID per request: it traces one request through the device log, the debug events, the error reports
     * and the server's log. Not a replay key; `/config` creates nothing.
     */
    val requestId: String,
    val body: JsonObject,
) {
    companion object {
        fun make(context: Context, configuration: Configuration): ConfigRequest {
            val requestId = UUID.randomUUID().toString()
            return ConfigRequest(
                requestId,
                buildJsonObject {
                    put("requestId", requestId)
                    put("publisherToken", configuration.publisherToken)
                    put("installId", DeviceInfo.installId(context))
                    put("sdk", SdkInfo.json())
                    put("app", DeviceInfo.app(context))
                    put(
                        "device",
                        buildJsonObject {
                            put("os", DeviceInfo.os(context))
                            put("hardware", DeviceInfo.hardware(context))
                        },
                    )
                },
            )
        }
    }
}
