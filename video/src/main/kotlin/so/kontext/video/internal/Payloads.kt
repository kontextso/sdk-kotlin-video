package so.kontext.video.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import so.kontext.video.AdFormat
import so.kontext.video.NoFillReason
import so.kontext.video.VideoAd

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

/** One served ad as the wire sends it. `VideoAd` is the public object built from it. */
internal class AdPayload(
    val id: String,
    val format: AdFormat,
    val creative: Creative,
    val price: VideoAd.Price,
    /** Milliseconds from receipt. */
    val expiresMillis: Long,
    val frameUrl: String,
    val tracking: List<TrackingEvent>,
    /** `null` for a linear ad. */
    val position: Position?,
    /** Every size the creative was built for, the default first; empty for a linear or full-screen pause ad. */
    val sizes: List<Size>,
    /** A linear ad's video length, or how long an overlay stays on screen; `null` for a pause ad and an overlay that stays. */
    val durationMillis: Long?,
    /** When a linear ad's Close may appear; `null` means not before the end. */
    val skipOffsetMillis: Long?,
) {
    /** What the page renders: decides the viewability clock, when the impression counts and the error codes. */
    enum class Creative { IMAGE, VIDEO, HTML }

    enum class Position { TOP, BOTTOM, FULLSCREEN }

    /** The box a creative was built for, in dp; `scalable: false` means never shown smaller. */
    data class Size(val width: Int, val height: Int, val scalable: Boolean = true)

    /** One beacon. `offsetMillis` for `progress`; `part` is `endCard` for a linear ad's end card. */
    data class TrackingEvent(val event: String, val url: String, val offsetMillis: Long?, val part: String?)

    companion object {
        /** The largest side a creative may have, in dp. */
        const val MAX_SIDE = 10_000

        private val refusedFrameSchemes = setOf("file", "javascript", "data", "blob", "about", "content")

        // One check per field of the contract.
        @Suppress("CyclomaticComplexMethod")
        fun parse(element: JsonElement): AdPayload {
            val ad = element.obj() ?: invalid("an ad is not an object")
            val id = ad.str("id")?.takeIf { it.isNotEmpty() } ?: invalid("the ad id is missing or empty")
            val format = AdFormat.fromWire(ad.str("format")) ?: invalid("ad $id: unknown format")
            val creative = when (ad.str("creative")) {
                "image" -> Creative.IMAGE
                "video" -> Creative.VIDEO
                "html" -> Creative.HTML
                else -> invalid("ad $id: unknown creative")
            }
            val price = ad["price"]?.obj()?.let { price ->
                VideoAd.Price(
                    value = price.double("value") ?: invalid("ad $id: price.value"),
                    currency = price.str("currency") ?: invalid("ad $id: price.currency"),
                    precision = when (price.str("precision")) {
                        "exact" -> VideoAd.Price.Precision.EXACT
                        "estimated" -> VideoAd.Price.Precision.ESTIMATED
                        else -> invalid("ad $id: price.precision")
                    },
                )
            } ?: invalid("ad $id: price")
            val expires = ad.long("expires") ?: invalid("ad $id: expires")
            // An ad that is expired on arrival is not an ad the server meant to give.
            if (expires <= 0) invalid("ad $id: expires must be positive")
            val tracking = ad["tracking"]?.arr()?.map { entry ->
                val event = entry.obj() ?: invalid("ad $id: a tracking entry is not an object")
                TrackingEvent(
                    event = event.str("event") ?: invalid("ad $id: tracking.event"),
                    url = event.str("url") ?: invalid("ad $id: tracking.url"),
                    offsetMillis = event.long("offset"),
                    part = event.str("part"),
                )
            } ?: invalid("ad $id: tracking")
            val trigger = ad["trigger"]?.obj() ?: invalid("ad $id: trigger")
            when (trigger.str("kind")) {
                "manual" -> Unit
                "window" -> if (trigger.long("from") == null || trigger.long("to") == null) invalid("ad $id: trigger window")
                else -> invalid("ad $id: unknown trigger kind")
            }
            val rendering = ad["rendering"]?.obj() ?: invalid("ad $id: rendering")
            if (rendering.str("kind") != "frame") invalid("ad $id: unknown rendering kind")
            val frameUrl = rendering.str("url") ?: invalid("ad $id: rendering.url")
            // A page to load: absolute, with a host, and not a scheme that loads something local or inline.
            val match = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]+)").find(frameUrl)
            if (match == null || match.groupValues[1].lowercase() in refusedFrameSchemes) invalid("ad $id: the frame URL must be absolute with a host")

            var position: Position? = null
            var sizes = emptyList<Size>()
            var duration: Long? = null
            var skipOffset: Long? = null
            when (format) {
                AdFormat.OVERLAY -> {
                    position = when (ad.str("position")) {
                        "top" -> Position.TOP
                        "bottom" -> Position.BOTTOM
                        else -> invalid("ad $id: overlay position")
                    }
                    sizes = sizes(ad, id)
                    duration = nonNegative(ad, "duration", id)
                }
                AdFormat.PAUSE -> {
                    position = when (ad.str("position")) {
                        "top" -> Position.TOP
                        "bottom" -> Position.BOTTOM
                        "fullscreen" -> Position.FULLSCREEN
                        else -> invalid("ad $id: pause position")
                    }
                    if (position != Position.FULLSCREEN) sizes = sizes(ad, id)
                }
                AdFormat.LINEAR -> {
                    duration = ad.long("duration") ?: invalid("ad $id: duration")
                    skipOffset = nonNegative(ad, "skipOffset", id)
                }
            }
            return AdPayload(id, format, creative, price, expires, frameUrl, tracking, position, sizes, duration, skipOffset)
        }

        private fun sizes(ad: JsonObject, id: String): List<Size> {
            val sizes = ad["sizes"]?.arr()?.map { element ->
                val size = element.obj() ?: invalid("ad $id: a size is not an object")
                val width = size.long("width") ?: invalid("ad $id: size.width")
                val height = size.long("height") ?: invalid("ad $id: size.height")
                // Positive, and no larger than any screen could be.
                if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) invalid("ad $id: a size must be positive and at most $MAX_SIDE")
                Size(width.toInt(), height.toInt(), size.bool("scalable") ?: true)
            } ?: invalid("ad $id: sizes")
            if (sizes.isEmpty()) invalid("ad $id: sizes must have at least one entry")
            return sizes
        }

        /** An optional count of milliseconds, refused when negative. */
        private fun nonNegative(ad: JsonObject, key: String, id: String): Long? {
            if (!ad.has(key)) return null
            val value = ad.long(key) ?: invalid("ad $id: $key")
            if (value < 0) invalid("ad $id: $key must not be negative")
            return value
        }
    }
}

internal fun invalid(reason: String): Nothing = throw ApiException.Decoding(IllegalArgumentException(reason))
