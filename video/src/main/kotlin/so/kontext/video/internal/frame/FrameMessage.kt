package so.kontext.video.internal.frame

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import so.kontext.video.internal.payloads.JsonParser
import so.kontext.video.internal.payloads.double
import so.kontext.video.internal.payloads.obj
import so.kontext.video.internal.payloads.str

/**
 * One text message of the ad's page, read: its envelope checked and its fields taken out, with no rule of the
 * protocol applied. What a message means in the page's state is [FrameProtocol]'s.
 *
 * Why apart: reading JSON (the limit, the envelope, the field types, the playhead's bounds) is a different job
 * from the protocol's rules, and each is tested as its own table (`FrameMessageTest`).
 *
 * ```
 *   raw ── over MAX_MESSAGE_BYTES ─┐
 *       ── not JSON, not an object ┼─► null: a broken envelope
 *       ── another ad's id, no type┘
 *       ── otherwise ──────────────► FrameMessage(type, playhead, offset, url, …)
 * ```
 */
internal class FrameMessage(
    /** `ad.loaded`, `ad.click`, `video.start`, ...; never checked against the protocol's types here. */
    val type: String,
    /**
     * Milliseconds into the ad video, a fraction rounded to the nearest (a page reads `currentTime * 1000`),
     * clamped to the limit given to [parse]; 0 when absent or not a number.
     */
    val playheadMillis: Long,
    /** `video.progress`'s checkpoint as sent, a JSON number (a string is not one); `null` otherwise. */
    val offset: Double?,
    /** A click's or an open's link. */
    val url: String?,
    /** A click's second link, when its first may not open. */
    val fallbackUrl: String?,
    /** `ad.error`'s code as sent. */
    val code: Double?,
    /** Where a click landed (`creative`, `endCard`, a label of the page's own). */
    val area: String?,
    /** Why the page asks to open a link (`ad.open`). */
    val purpose: String?,
    /** Why the page closed itself (`ad.close`). */
    val reason: String?,
) {
    companion object {
        /** The largest page message parsed, in UTF-8 bytes; every message of the protocol is far smaller. */
        const val MAX_MESSAGE_BYTES = 1024 * 1024

        /**
         * Reads [raw] as a message of the page of ad [adId], its playhead clamped to [playheadLimitMillis].
         * `null` for a broken envelope: over [MAX_MESSAGE_BYTES] (never parsed, as on iOS), not a JSON object,
         * another ad's id, or no type.
         */
        fun parse(raw: String?, adId: String, playheadLimitMillis: Long): FrameMessage? {
            val body = raw?.takeIf(::fitsLimit)?.let { runCatching { JsonParser.parse(it) }.getOrNull() }?.obj() ?: return null
            val type = body.str("type")
            if (body.str("adId") != adId || type == null) return null
            return FrameMessage(
                type = type,
                // Clamped: a playhead past the end would fire every checkpoint at once.
                playheadMillis = Math.round((body.double("playhead") ?: 0.0).coerceIn(0.0, playheadLimitMillis.toDouble())),
                offset = (body["offset"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
                url = body.str("url"),
                fallbackUrl = body.str("fallbackUrl"),
                code = body.double("code"),
                area = body.str("area"),
                purpose = body.str("purpose"),
                reason = body.str("reason"),
            )
        }

        /** Whether a message is within [MAX_MESSAGE_BYTES] in UTF-8; never fewer bytes than UTF-16 units, so a long one is out at once. */
        private fun fitsLimit(raw: String): Boolean = raw.length <= MAX_MESSAGE_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES
    }
}
