package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How the SDK's reports write their fields, shared by [ErrorReporter] and [DebugReporter] so neither reaches into the other. */
internal object ReportFormat {
    /** UTF-8 bytes kept of every string field of an error report. */
    const val MAX_FIELD_LENGTH = 64

    /** [text] cut to [MAX_FIELD_LENGTH] UTF-8 bytes, at a code point boundary so it stays valid text. */
    fun capped(text: String): String {
        if (text.toByteArray().size <= MAX_FIELD_LENGTH) return text
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val size = piece.toByteArray().size
            if (bytes + size > MAX_FIELD_LENGTH) break
            result.append(piece)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    /** `2026-10-21T18:00:00.482Z`. */
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    /** A state value as JSON. */
    fun json(value: Any): JsonPrimitive = when (value) {
        // A NaN or infinite number has no JSON form: written as text, so the body stays JSON.
        is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
