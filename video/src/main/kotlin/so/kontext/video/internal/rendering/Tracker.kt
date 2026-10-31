package so.kontext.video.internal.rendering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.AdPayload
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Fires an ad's tracking entries when their moment happens, once each except the events that repeat, with
 * the macros substituted: the IAB's (`[CACHEBUSTING]`, `[TIMESTAMP]`, `[MEDIAPLAYHEAD]` and its deprecated
 * twin `[CONTENTPLAYHEAD]`, `[ADPLAYHEAD]`, `[ERRORCODE]`) and our own, prefixed `KONTEXT_`. A macro the SDK
 * does not know stays in place. The page never fires a beacon; the SDK fires every one. No retries.
 */
internal class Tracker(private val adId: String, private val entries: List<AdPayload.TrackingEvent>) {
    /** Milliseconds into the ad video, as the page last reported it; `[ADPLAYHEAD]`. */
    var adPlayheadMillis = 0L

    /** Content time in milliseconds; `[MEDIAPLAYHEAD]`. The app's player behind it is app code, read once per operation. */
    var contentPlayhead: () -> Long = { 0 }

    /** The content playhead read before the operation now running; every beacon of it uses this value. */
    var frozenContentPlayhead: Long? = null

    /** The last value the app's player gave. */
    var lastContentPlayhead = 0L

    /** Whether any URL asks for the content playhead; when none does, nobody reads it. */
    val readsContentPlayhead: Boolean = entries.any { asksForContentPlayhead(it.url) }

    private val fired = mutableSetOf<Int>()

    /** Why the SDK did not use a bid, for the `notUsed` beacon. */
    sealed class NotUsed(val reason: String, val winningPrice: Double? = null, val winner: String? = null) {
        object NotUsedByApp : NotUsed("notUsed")
        class Outbid(winningPrice: Double?, winner: String?) : NotUsed("outbid", winningPrice, winner)
        object Destroyed : NotUsed("destroyed")
    }

    /**
     * Fires every entry named [event] for [part] (`null` is the main creative) that has not fired yet. Returns
     * the occurrence's fire id, for a click-through that must carry the same one.
     */
    fun fire(
        event: String,
        part: String? = null,
        offsetMillis: Long? = null,
        errorCode: Int? = null,
        viewDurationMillis: Long? = null,
        notUsed: NotUsed? = null,
    ): String {
        // One fire id per occurrence, shared by every URL of it: the server dedupes on (adId, event, fid).
        val fireId = UUID.randomUUID().toString()
        entries.forEachIndexed { index, entry ->
            if (index in fired || entry.event != event || entry.part != part) return@forEachIndexed
            if (offsetMillis != null && entry.offsetMillis != offsetMillis) return@forEachIndexed
            if (event !in REPEATABLE) fired += index
            send(substitute(entry.url, errorCode, fireId, viewDurationMillis, notUsed), part?.let { "$event ($it)" } ?: event)
        }
        return fireId
    }

    /** Fires every `progress` entry the ad video has passed and not fired yet, each as its own occurrence, in order. */
    fun fireProgress(upToMillis: Long) {
        val due = entries.indices.mapNotNull { index ->
            val entry = entries[index]
            if (entry.event == "progress" && index !in fired && entry.offsetMillis != null && entry.offsetMillis <= upToMillis) entry.offsetMillis else null
        }.toSortedSet()
        due.forEach { fire("progress", offsetMillis = it) }
    }

    /** The click-through with the beacon macros substituted, `[KONTEXT_FIREID]` with the click's own fire id. */
    fun clickThrough(url: String, fireId: String): String = substitute(url, null, fireId, null, null)

    private fun send(url: String, label: String) {
        Logger.debug { "ad ${adId.take(8)}: beacon $label" }
        Environment.sendBeacon(url, "ad ${adId.take(8)}: $label")
    }

    private fun substitute(template: String, errorCode: Int?, fireId: String, viewDurationMillis: Long?, notUsed: NotUsed?): String {
        // A macro may arrive with its brackets encoded: normalized first, re-encoded at the end.
        val normalized = template.replace("%5B", "[", ignoreCase = true).replace("%5D", "]", ignoreCase = true)
        val content = if (asksForContentPlayhead(normalized)) encode(playhead(frozenContentPlayhead ?: contentPlayhead())) else ""
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

    companion object {
        /** Events that fire every time they happen; everything else fires once per entry. */
        val REPEATABLE = setOf("click", "pause", "resume", "mute", "unmute")
        private const val TIMEOUT_MILLIS = 10_000

        /** A GET of [url] off the main thread, once: no retries. [label] names it in the log. */
        fun get(url: String, label: String) {
            Environment.scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = URL(url).openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = TIMEOUT_MILLIS
                            connection.readTimeout = TIMEOUT_MILLIS
                            connection.useCaches = false
                            val status = connection.responseCode
                            if (status >= 400) Logger.warning { "$label beacon answered $status" }
                        } finally {
                            connection.disconnect()
                        }
                    }.onFailure { Logger.warning { "$label beacon failed, ${it.javaClass.simpleName}" } }
                }
            }
        }

        /** Playheads beyond ten years are nonsense and clamped. */
        const val MAX_PLAYHEAD_MILLIS = 315_360_000_000L

        fun asksForContentPlayhead(template: String): Boolean {
            val normalized = template.replace("%5B", "[", ignoreCase = true).replace("%5D", "]", ignoreCase = true)
            return "[MEDIAPLAYHEAD]" in normalized || "[CONTENTPLAYHEAD]" in normalized
        }

        /** `[MEDIAPLAYHEAD]`, `[ADPLAYHEAD]`: `HH:MM:SS.mmm`. */
        fun playhead(millis: Long): String {
            val total = millis.coerceIn(0, MAX_PLAYHEAD_MILLIS)
            return "%02d:%02d:%02d.%03d".format(Locale.US, total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, total % 1000)
        }

        /** `[TIMESTAMP]`: `YYYY-MM-DDTHH:MM:SS.mmm±HH:MM` in the device's zone. */
        fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

        /** A value inside one query parameter: only RFC 3986's unreserved characters stay. */
        /**
         * A number as the Swift SDK writes it, so both send the same macro: decimals from 1e-4 to 2^53 (`3.0`,
         * `0.0005`), the shortest exponent form beyond (`1e-05`, `1e+20`), and `nan`, `inf`.
         */
        fun number(value: Double): String {
            if (value.isNaN()) return "nan"
            if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
            if (value == 0.0) return if (1 / value < 0) "-0.0" else "0.0"
            val digits = BigDecimal(value.toString()).stripTrailingZeros()
            if (abs(value) in 1e-4..9_007_199_254_740_992.0) {
                val plain = digits.toPlainString()
                return if ('.' in plain) plain else "$plain.0"
            }
            val exponent = digits.precision() - digits.scale() - 1
            val sign = if (exponent < 0) "-" else "+"
            return "${digits.movePointLeft(exponent).toPlainString()}e$sign${abs(exponent).toString().padStart(2, '0')}"
        }

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    }
}
