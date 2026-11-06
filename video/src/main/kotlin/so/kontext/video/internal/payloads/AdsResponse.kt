package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonElement
import so.kontext.video.AdFormat
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.network.ApiException

/**
 * The answer to `POST /video/ads`: one entry per format (the SDK always asks for all of them), each with its
 * ads or its no-fill reason; or the kill switch. Strict on the envelope (`enabled`, `formats`, each entry's
 * `format` and `ads`), a format listed twice and an ad id listed twice, which fail the whole answer. Each ad is
 * read on its own: a malformed one is dropped, and leaves its format a no-fill when nothing valid is left; a
 * result for a format the SDK does not know is ignored. Lenient on the reason.
 *
 * Why two levels: the envelope says what the answer is, so a broken or contradictory one cannot be trusted at all
 * (`Request(BadResponse)`, no ads). An ad is self-contained, so one bad ad (no price, a bad URL) should not cost
 * the others: it is dropped and reported, and every other ad and format is used. An unknown format is skipped so
 * that a newer server, offering a format this SDK version lacks, never breaks an older app. For example, an
 * answer with a valid overlay, a pause ad without a price and a `banner` entry gives the overlay, a pause no-fill,
 * and nothing for `banner`. The shared malformed-answer corpus checks these rules in both SDKs.
 */
internal sealed class AdsResponse {
    /** [dropped]: each malformed ad of the format that was left out, for the log and its error report. */
    class FormatResult(val format: AdFormat, val ads: List<AdPayload>, val noFillReason: NoFillReason?, val dropped: List<Dropped> = emptyList())

    /** A malformed ad left out: [path] is the field that failed inside it (`price.currency`), [reason] says why. */
    class Dropped(val path: String, val reason: String) {
        override fun toString(): String = reason
    }

    class Formats(val results: List<FormatResult>) : AdsResponse()

    class Disabled(val reason: String) : AdsResponse()

    /** The entry for one format, or `null` when the server did not answer for it. */
    fun result(format: AdFormat): FormatResult? = (this as? Formats)?.results?.firstOrNull { it.format == format }

    companion object {
        fun parse(json: JsonElement?): AdsResponse {
            val body = json?.obj() ?: invalid("not an object")
            val isEnabled = body.bool("enabled") ?: invalid("enabled is missing")
            if (!isEnabled) return Disabled(body.str("disabledReason")?.takeIf { it.isNotBlank() } ?: UNKNOWN_REASON)
            val results = body["formats"]?.arr()?.mapNotNull(::entry) ?: invalid("formats is missing")
            // One entry per format: with two, which one counts would depend on their order.
            if (results.map { it.format }.toSet().size != results.size) invalid("a format is listed twice")
            // One id is one bid: under two formats, one could be rejected and the other shown.
            val ids = results.flatMap { result -> result.ads.map { it.id } }
            ids.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { invalid("ad ${it.key} is listed twice") }
            return Formats(results)
        }

        /** One format's result; `null` for a format the SDK does not know. */
        private fun entry(element: JsonElement): FormatResult? {
            val entry = element.obj() ?: invalid("a format entry is not an object")
            val wire = entry.str("format") ?: invalid("a format entry has no format")
            // A format of a later contract (one this SDK cannot show) costs the other formats nothing, whatever
            // its entry holds: its shape is that contract's, so nothing past the name is read.
            val format = AdFormat.fromWire(wire) ?: return null
            val rawAds = entry["ads"]?.arr() ?: invalid("ads is missing")
            val dropped = mutableListOf<Dropped>()
            val ads = rawAds.mapNotNull { raw ->
                try {
                    // The SDK files the ad under the result's format and finds it again by the ad's own: both must agree.
                    AdPayload.parse(raw).also {
                        if (it.format != format) throw ApiException.Decoding(AdPayload.MalformedField("format", "ad ${it.id} is ${it.format}, the result is $format"))
                    }
                } catch (error: ApiException.Decoding) {
                    val cause = error.cause
                    dropped += Dropped((cause as? AdPayload.MalformedField)?.path ?: "ad", cause?.message ?: "malformed")
                    null
                }
            }
            val reason = when {
                ads.isNotEmpty() -> null
                // Ads came and none could be read: the server's reason would be about something else.
                dropped.isNotEmpty() -> NoFillReason.Unknown
                else -> noFillReasonFrom(entry.str("noFillReason"))
            }
            return FormatResult(format, ads, reason, dropped)
        }
    }
}

// Lazy: built while NoFillReason itself is still initializing, the list would hold nulls for its objects.
private val knownNoFillReasons by lazy {
    listOf(
        NoFillReason.NoAdMatch,
        NoFillReason.ContentUnsafe,
        NoFillReason.FormatNotEnabled,
        NoFillReason.FrequencyCap,
        NoFillReason.GeoRestricted,
        NoFillReason.TemporaryError,
        NoFillReason.AlreadyUsed,
        NoFillReason.Unknown,
    )
}

/** A no-fill reason from the wire; `null` (left out or malformed) is [NoFillReason.Unknown], one this SDK does not know [NoFillReason.Other]. */
internal fun noFillReasonFrom(raw: String?): NoFillReason = when {
    // Missing, empty or blank: no reason given, as Swift reads it.
    raw.isNullOrBlank() -> NoFillReason.Unknown
    else -> knownNoFillReasons.firstOrNull { it.wireName == raw } ?: NoFillReason.Other(raw)
}
