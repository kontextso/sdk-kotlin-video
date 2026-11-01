package so.kontext.video.internal.wire

import kotlinx.serialization.json.JsonElement
import so.kontext.video.AdFormat
import so.kontext.video.NoFillReason
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.arr
import so.kontext.video.internal.network.bool
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str

/**
 * The answer to `POST /video/ads`: one entry per format (the SDK always asks for all of them), each with its
 * ads or its no-fill reason; or the kill switch. Strict on the envelope (`enabled`, `formats`, each entry's
 * `format` and `ads`) and a duplicate format, which fail the whole answer. Each ad is
 * read on its own: a malformed one is dropped, and leaves its format a no-fill when nothing valid is left; a
 * result for a format the SDK does not know is ignored. Lenient on the reason.
 */
internal sealed class AdsResponse {
    /** [dropped]: why each malformed ad of the format was left out, for the log and one error report. */
    class FormatResult(val format: AdFormat, val ads: List<AdPayload>, val noFillReason: NoFillReason?, val dropped: List<String> = emptyList())

    class Formats(val results: List<FormatResult>) : AdsResponse()

    class Disabled(val reason: String) : AdsResponse()

    /** The entry for one format, or `null` when the server did not answer for it. */
    fun result(format: AdFormat): FormatResult? = (this as? Formats)?.results?.firstOrNull { it.format == format }

    companion object {
        fun parse(json: JsonElement?): AdsResponse {
            val body = json?.obj() ?: invalid("not an object")
            val enabled = body.bool("enabled") ?: invalid("enabled is missing")
            if (!enabled) return Disabled(body.str("disabledReason") ?: UNKNOWN_REASON)
            val results = body["formats"]?.arr()?.mapNotNull(::entry) ?: invalid("formats is missing")
            // One entry per format: with two, which one counts would depend on their order.
            if (results.map { it.format }.toSet().size != results.size) invalid("a format is listed twice")
            return Formats(results)
        }

        /** One format's result; `null` for a format the SDK does not know. */
        private fun entry(element: JsonElement): FormatResult? {
            val entry = element.obj() ?: invalid("a format entry is not an object")
            val wire = entry.str("format") ?: invalid("a format entry has no format")
            val rawAds = entry["ads"]?.arr() ?: invalid("ads is missing")
            // A format of a later contract (one this SDK cannot show) costs the other formats nothing.
            val format = AdFormat.fromWire(wire) ?: return null
            val dropped = mutableListOf<String>()
            val ads = rawAds.mapNotNull { raw ->
                try {
                    // The SDK files the ad under the result's format and finds it again by the ad's own: both must agree.
                    AdPayload.parse(raw).also { if (it.format != format) invalid("ad ${it.id} is ${it.format}, the result is $format") }
                } catch (error: ApiException.Decoding) {
                    dropped += error.cause?.message ?: "malformed"
                    null
                }
            }
            val reason = when {
                ads.isNotEmpty() -> null
                // Ads came and none could be read: the server's reason would be about something else.
                dropped.isNotEmpty() -> NoFillReason.Unknown
                else -> NoFillReason.from(entry.str("noFillReason"))
            }
            return FormatResult(format, ads, reason, dropped)
        }
    }
}
