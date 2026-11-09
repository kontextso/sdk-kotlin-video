package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import so.kontext.video.internal.network.ApiException

/**
 * The answer to `POST /config`.
 *
 * `enabled` is required: without it the answer is a `Decoding` error, as in the Swift SDK. It is the kill switch,
 * and a missing one cannot be read either way: as `true`, a broken answer would ignore a server switching ads off
 * (a bad creative in the wild, a legal problem); as `false`, one malformed answer would disable the SDK for the
 * whole app launch. Failing instead leaves `/config` failed, and the next `format()` asks again. The extras are
 * lenient: `reportDebug` and `reportErrors` default to `false`, the batch intervals to
 * [DEFAULT_BATCH_INTERVAL_MILLIS], `disabledReason` to [UNKNOWN_REASON]. The same rule holds for `enabled` in the
 * ads answer (`AdsResponse`).
 */
internal sealed class ConfigResponse {
    /**
     * `reportDebug`: send the debug log to `/debug`; `reportErrors`: send error reports to `/error`; both false when
     * left out. The intervals say how long reports and events are collected before a batch leaves; 0 sends each as
     * it happens.
     */
    data class Enabled(
        val reportDebug: Boolean,
        val reportErrors: Boolean,
        val errorBatchIntervalMillis: Long,
        val debugBatchIntervalMillis: Long,
    ) : ConfigResponse()

    /** The server switched the SDK off. */
    data class Disabled(val reason: String) : ConfigResponse()

    companion object {
        /** The wait before a batch leaves when the server names none. */
        const val DEFAULT_BATCH_INTERVAL_MILLIS = 100L

        fun parse(json: JsonElement?): ConfigResponse {
            val body = json?.obj() ?: throw ApiException.Decoding(IllegalArgumentException("not an object"))
            val isEnabled = body.bool("enabled") ?: throw ApiException.Decoding(IllegalArgumentException("enabled is missing"))
            return if (isEnabled) {
                Enabled(
                    reportDebug = body.bool("reportDebug") ?: false,
                    reportErrors = body.bool("reportErrors") ?: false,
                    errorBatchIntervalMillis = body.intervalMillis("errorBatchInterval"),
                    debugBatchIntervalMillis = body.intervalMillis("debugBatchInterval"),
                )
            } else {
                Disabled(body.str("disabledReason")?.takeIf { it.isNotBlank() } ?: UNKNOWN_REASON)
            }
        }

        /** A batch interval as the server writes it, in milliseconds; absent, not a number or negative means the default. */
        private fun JsonObject.intervalMillis(key: String): Long =
            double(key)?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: DEFAULT_BATCH_INTERVAL_MILLIS
    }
}
