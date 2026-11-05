package so.kontext.video.internal.measurement

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * The macros of a tracking URL, filled in: the IAB's (`[CACHEBUSTING]`, `[TIMESTAMP]`, `[MEDIAPLAYHEAD]` and its
 * deprecated twin `[CONTENTPLAYHEAD]`, `[ADPLAYHEAD]`, `[ERRORCODE]`) and our own, prefixed `KONTEXT_`. A macro the
 * SDK does not know stays in place, its brackets encoded. Pure: [Tracker] decides when a beacon fires and with
 * which values.
 */
internal object TrackingMacros {
    /** Playheads beyond ten years are nonsense and clamped; the largest count of milliseconds any field may hold. */
    const val MAX_PLAYHEAD_MILLIS = 315_360_000_000L

    /** Seventeen significant digits always identify a Double. */
    private const val MAX_DIGITS = 17

    /**
     * [template] with every macro filled in. [contentPlayhead] is read only when the URL asks for it. A macro may
     * arrive with its brackets encoded: normalized first ([decodingBrackets]), re-encoded at the end.
     */
    fun substitute(
        template: String,
        contentPlayhead: () -> Long,
        adPlayheadMillis: Long,
        errorCode: Int?,
        fireId: String,
        viewDurationMillis: Long?,
        notUsed: Tracker.NotUsed?,
    ): String {
        val normalized = decodingBrackets(template)
        val content = if (asksForContentPlayhead(normalized)) encode(playhead(contentPlayhead())) else ""
        return normalized
            .replace("[CACHEBUSTING]", "%08d".format(Locale.US, Random.nextInt(100_000_000)))
            .replace("[TIMESTAMP]", encode(timestamp()))
            .replace("[MEDIAPLAYHEAD]", content)
            .replace("[CONTENTPLAYHEAD]", content)
            .replace("[ADPLAYHEAD]", encode(playhead(adPlayheadMillis)))
            .replace("[ERRORCODE]", errorCode?.toString() ?: "")
            .replace("[KONTEXT_FIREID]", fireId)
            .replace("[KONTEXT_VIEWDURATION]", viewDurationMillis?.toString() ?: "")
            .replace("[KONTEXT_REASON]", notUsed?.reason ?: "")
            .replace("[KONTEXT_WINNINGPRICE]", encode(notUsed?.winningPrice?.let(::number) ?: ""))
            .replace("[KONTEXT_WINNER]", encode(notUsed?.winner ?: ""))
            .replace("[", "%5B")
            .replace("]", "%5D")
    }

    /** Whether [template] asks for the content playhead (`[MEDIAPLAYHEAD]` or `[CONTENTPLAYHEAD]`), its brackets encoded or not. */
    fun asksForContentPlayhead(template: String): Boolean {
        val normalized = decodingBrackets(template)
        return "[MEDIAPLAYHEAD]" in normalized || "[CONTENTPLAYHEAD]" in normalized
    }

    /** [template] with encoded brackets (`%5B`, `%5D`, either case) written as brackets, so a macro is found either way. */
    fun decodingBrackets(template: String): String = template.replace("%5B", "[", ignoreCase = true).replace("%5D", "]", ignoreCase = true)

    /** `[MEDIAPLAYHEAD]`, `[ADPLAYHEAD]`: `HH:MM:SS.mmm`. */
    fun playhead(millis: Long): String {
        val total = millis.coerceIn(0, MAX_PLAYHEAD_MILLIS)
        return "%02d:%02d:%02d.%03d".format(Locale.US, total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, total % 1000)
    }

    /** `[TIMESTAMP]`: `YYYY-MM-DDTHH:MM:SS.mmm±HH:MM` in the device's zone. */
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /**
     * A number as the Swift SDK writes it, so both send the same macro: decimals from 1e-4 to 2^53 (`3.0`,
     * `0.0005`), the shortest exponent form beyond (`1e-05`, `1e+20`), and `nan`, `inf`.
     */
    fun number(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        if (value == 0.0) return if (1 / value < 0) "-0.0" else "0.0"
        val digits = shortestDigits(value)
        if (abs(value) in 1e-4..9_007_199_254_740_992.0) {
            val plain = digits.toPlainString()
            return if ('.' in plain) plain else "$plain.0"
        }
        val exponent = digits.precision() - digits.scale() - 1
        val sign = if (exponent < 0) "-" else "+"
        return "${digits.movePointLeft(exponent).toPlainString()}e$sign${abs(exponent).toString().padStart(2, '0')}"
    }

    /** A value inside one query parameter: only RFC 3986's unreserved characters stay. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    /**
     * The fewest significant digits that read back as [value], the closest such to its exact value: what
     * Swift prints. At each precision the exact value rounded half-even, or, where that misses (the narrower
     * gap below a power of two), its neighbour one last digit up or down. Not `Double.toString` (not always
     * the shortest, `1e23`) nor `String.format`, which rounds differently in some cases.
     */
    private fun shortestDigits(value: Double): BigDecimal {
        val exact = BigDecimal(value)
        for (precision in 1..MAX_DIGITS) {
            val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            val step = BigDecimal.ONE.scaleByPowerOfTen(rounded.precision() - rounded.scale() - precision)
            val match = listOf(rounded, rounded.subtract(step), rounded.add(step))
                .filter { it.toDouble() == value }
                .minByOrNull { it.subtract(exact).abs() }
            if (match != null) return match.stripTrailingZeros()
        }
        return exact.stripTrailingZeros()
    }
}
