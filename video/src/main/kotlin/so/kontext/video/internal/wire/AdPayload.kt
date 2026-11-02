package so.kontext.video.internal.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.internal.network.arr
import so.kontext.video.internal.network.bool
import so.kontext.video.internal.network.double
import so.kontext.video.internal.network.has
import so.kontext.video.internal.network.long
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str
import java.net.URI
import java.net.URISyntaxException

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

        private val CURRENCY = Regex("[A-Z]{3}")

        /**
         * The schemes a frame page may load from. An allowlist: the bridge's origin rule accepts only a web
         * origin, so any other scheme would make the WebView refuse the bridge; and a page is a web page.
         */
        private val frameSchemes = setOf("http", "https")

        /** Why [url] cannot be a frame page, or `null`: it must be http or https, with a host and a port from 1 to 65535 (or none). */
        fun frameUrlProblem(url: String): String? {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return "the frame URL is not a URL"
            }
            if (uri.scheme?.lowercase() !in frameSchemes) return "the frame URL must be http or https"
            if (uri.host.isNullOrEmpty()) return "the frame URL must have a host"
            if (uri.port != -1 && uri.port !in 1..MAX_PORT) return "the frame URL's port must be from 1 to $MAX_PORT"
            return null
        }

        private const val MAX_PORT = 65_535

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
            // A CPM: a number, not infinity (`1e400`), and never negative. ISO 4217: three capital letters.
            if (!price.value.isFinite() || price.value < 0) invalid("ad $id: price.value must be finite and not negative")
            if (!CURRENCY.matches(price.currency)) invalid("ad $id: price.currency must be an ISO 4217 code")
            val expires = millis(ad, "expires") ?: invalid("ad $id: expires")
            // An ad that is expired on arrival is not an ad the server meant to give.
            if (expires <= 0) invalid("ad $id: expires must be positive")
            val tracking = ad["tracking"]?.arr()?.mapNotNull { entry ->
                val event = entry.obj() ?: invalid("ad $id: a tracking entry is not an object")
                val name = event.str("event") ?: invalid("ad $id: tracking.event")
                val url = event.str("url") ?: invalid("ad $id: tracking.url")
                // An offset that is no count of milliseconds loses its entry, never the ad.
                val offset = if (event.has("offset")) offsetMillis(event) ?: return@mapNotNull null else null
                TrackingEvent(event = name, url = url, offsetMillis = offset, part = event.str("part"))
            } ?: invalid("ad $id: tracking")
            val trigger = ad["trigger"]?.obj() ?: invalid("ad $id: trigger")
            when (trigger.str("kind")) {
                "manual" -> Unit
                "window" -> if (millis(trigger, "from") == null || millis(trigger, "to") == null) invalid("ad $id: trigger window")
                else -> invalid("ad $id: unknown trigger kind")
            }
            val rendering = ad["rendering"]?.obj() ?: invalid("ad $id: rendering")
            if (rendering.str("kind") != "frame") invalid("ad $id: unknown rendering kind")
            val frameUrl = rendering.str("url") ?: invalid("ad $id: rendering.url")
            frameUrlProblem(frameUrl)?.let { invalid("ad $id: $it") }

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
                    duration = millis(ad, "duration") ?: invalid("ad $id: duration")
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
                // Absent is scalable; a flag of the wrong type is a malformed size, not a guess.
                val scalable = if (size.has("scalable")) size.bool("scalable") ?: invalid("ad $id: size.scalable") else true
                Size(width.toInt(), height.toInt(), scalable)
            } ?: invalid("ad $id: sizes")
            if (sizes.isEmpty()) invalid("ad $id: sizes must have at least one entry")
            return sizes
        }

        /**
         * A tracking entry's `offset`: any finite, non-negative number of milliseconds (the server converts it
         * from a percent, so 4957.59 is fine), rounded to the nearest one; `null` for a value that is not a
         * number, negative, or beyond ten years.
         */
        private fun offsetMillis(event: JsonObject): Long? {
            val value = event.double("offset") ?: return null
            if (value < 0) return null
            return millis(value)
        }

        /** The largest count of milliseconds taken: ten years, as the playheads are clamped. Beyond it a value is nonsense. */
        private const val MAX_MILLIS = 315_360_000_000.0

        /**
         * A wire `number` of milliseconds as whole ones, rounded to the nearest: the contract types durations and
         * offsets as numbers, and a server converting from VAST or a percent sends fractions. `null` when not
         * finite or beyond ten years either way, as in the Swift SDK.
         */
        private fun millis(value: Double): Long? {
            if (!value.isFinite() || kotlin.math.abs(value) > MAX_MILLIS) return null
            return Math.round(value)
        }

        /** [key] of [obj] as milliseconds ([millis]); `null` when absent or not a number. */
        private fun millis(obj: JsonObject, key: String): Long? = obj.double(key)?.let(::millis)

        /** An optional count of milliseconds, refused when negative. */
        private fun nonNegative(ad: JsonObject, key: String, id: String): Long? {
            if (!ad.has(key)) return null
            val value = millis(ad, key) ?: invalid("ad $id: $key")
            if (value < 0) invalid("ad $id: $key must not be negative")
            return value
        }
    }
}
