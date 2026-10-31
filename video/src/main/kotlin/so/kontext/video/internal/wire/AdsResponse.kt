package so.kontext.video.internal.wire

import kotlinx.serialization.json.JsonElement
import so.kontext.video.AdFormat
import so.kontext.video.NoFillReason
import so.kontext.video.internal.network.arr
import so.kontext.video.internal.network.bool
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str

/**
 * The answer to `POST /video/ads`: one entry per format (the SDK always asks for all of them), each with its
 * ads or its no-fill reason; or the kill switch. Strict on `format` and `ads`, lenient on the reason.
 */
internal sealed class AdsResponse {
    class FormatResult(val format: AdFormat, val ads: List<AdPayload>, val noFillReason: NoFillReason?)

    class Formats(val results: List<FormatResult>) : AdsResponse()

    class Disabled(val reason: String) : AdsResponse()

    /** The entry for one format, or `null` when the server did not answer for it. */
    fun result(format: AdFormat): FormatResult? = (this as? Formats)?.results?.firstOrNull { it.format == format }

    companion object {
        fun parse(json: JsonElement?): AdsResponse {
            val body = json?.obj() ?: invalid("not an object")
            val enabled = body.bool("enabled") ?: invalid("enabled is missing")
            if (!enabled) return Disabled(body.str("disabledReason") ?: UNKNOWN_REASON)
            val results = body["formats"]?.arr()?.map { element ->
                val entry = element.obj() ?: invalid("a format entry is not an object")
                val format = AdFormat.fromWire(entry.str("format")) ?: invalid("unknown format ${entry["format"]}")
                val ads = entry["ads"]?.arr()?.map { AdPayload.parse(it) } ?: invalid("ads is missing")
                // The SDK files the ad under the result's format and finds it again by the ad's own: both must agree.
                ads.firstOrNull { it.format != format }?.let { invalid("ad ${it.id} is ${it.format}, the result is $format") }
                FormatResult(format, ads, if (ads.isEmpty()) NoFillReason.from(entry.str("noFillReason")) else null)
            } ?: invalid("formats is missing")
            // One entry per format: with two, which one counts would depend on their order.
            if (results.map { it.format }.toSet().size != results.size) invalid("a format is listed twice")
            return Formats(results)
        }
    }
}
