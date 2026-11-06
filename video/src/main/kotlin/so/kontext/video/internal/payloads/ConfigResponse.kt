package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonElement
import so.kontext.video.internal.network.ApiException

/**
 * The answer to `POST /config`.
 *
 * `enabled` is required: without it the answer is a `Decoding` error, as in the Swift SDK. It is the kill switch,
 * and a missing one cannot be read either way: as `true`, a broken answer would ignore a server switching ads off
 * (a bad creative in the wild, a legal problem); as `false`, one malformed answer would disable the SDK for the
 * whole app launch. Failing instead leaves `/config` failed, and the next `format()` asks again. The extras are
 * lenient: `reportDebug` and `reportErrors` default to `false`, `disabledReason` to [UNKNOWN_REASON]. The same
 * rule holds for `enabled` in the ads answer (`AdsResponse`).
 */
internal sealed class ConfigResponse {
    /** `reportDebug`: send the debug log to `/debug`; `reportErrors`: send error reports to `/error`. Both false when left out. */
    data class Enabled(val reportDebug: Boolean, val reportErrors: Boolean) : ConfigResponse()

    /** The server switched the SDK off. */
    data class Disabled(val reason: String) : ConfigResponse()

    companion object {
        fun parse(json: JsonElement?): ConfigResponse {
            val body = json?.obj() ?: throw ApiException.Decoding(IllegalArgumentException("not an object"))
            val isEnabled = body.bool("enabled") ?: throw ApiException.Decoding(IllegalArgumentException("enabled is missing"))
            return if (isEnabled) {
                Enabled(body.bool("reportDebug") ?: false, body.bool("reportErrors") ?: false)
            } else {
                Disabled(body.str("disabledReason")?.takeIf { it.isNotBlank() } ?: UNKNOWN_REASON)
            }
        }
    }
}
