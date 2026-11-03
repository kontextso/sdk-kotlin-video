package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonElement
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.bool
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str

/** The answer to `POST /config`. */
internal sealed class ConfigResponse {
    /** `reportDebug`: send the debug log to `/debug`; `reportErrors`: send error reports to `/error`. Both false when left out. */
    data class Enabled(val reportDebug: Boolean, val reportErrors: Boolean) : ConfigResponse()

    /** The server switched the SDK off. */
    data class Disabled(val reason: String) : ConfigResponse()

    companion object {
        fun parse(json: JsonElement?): ConfigResponse {
            val body = json?.obj() ?: throw ApiException.Decoding(IllegalArgumentException("not an object"))
            val enabled = body.bool("enabled") ?: throw ApiException.Decoding(IllegalArgumentException("enabled is missing"))
            return if (enabled) {
                Enabled(body.bool("reportDebug") ?: false, body.bool("reportErrors") ?: false)
            } else {
                Disabled(body.str("disabledReason") ?: UNKNOWN_REASON)
            }
        }
    }
}

/** Stands in for a `disabledReason` the server left out or sent malformed. */
internal const val UNKNOWN_REASON = "unknown"

internal fun invalid(reason: String): Nothing = throw ApiException.Decoding(IllegalArgumentException(reason))
