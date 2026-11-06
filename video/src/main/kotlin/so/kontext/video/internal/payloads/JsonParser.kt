package so.kontext.video.internal.payloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import so.kontext.video.internal.network.ApiException

/**
 * Parses JSON into a tree (`Json.parseToJsonElement`, kotlinx.serialization's equivalent of `JSON.parse`), strictly,
 * with a guard on how deep it nests. Every JSON the SDK reads from outside goes through it: the server's answers
 * (`ApiClient`, which turns a failure into `ApiException.Decoding`), its error bodies (`ErrorResponse`) and the
 * ad page's messages (`FrameMessage`).
 *
 * Why strict: kotlinx's parser is lenient where iOS's JSON decoder is not, so the two SDKs read one answer two
 * ways. It takes unquoted literals (`TRUE` and `True` read as `true`, which would turn the SDK on; `NaN`,
 * `Infinity`, `abc`), numbers with leading zeros (`01`), and keeps the last of two equal keys (iOS keeps the
 * first). Before parsing, [validate] reads the text once and refuses all of it:
 *
 * ```
 *   outside strings, a bare token is   true | false | null | -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?
 *   {"enabled": TRUE}  {"a": NaN}  {"a": 01}  {"a": abc}       refused: not JSON
 *   {"enabled": false, "enabled": true}                         refused: a key twice in one object
 *   {"a": "x<a raw newline>y"}                                  refused: a control character not escaped
 * ```
 *
 * Why the depth guard: the parser is recursive, one call per level. A text like `[[[[…]]]]` nested 100 000 deep
 * would exhaust the stack and throw `StackOverflowError`, an `Error` that no `catch (Exception)` stops, crashing
 * the app from an ad request or a page message. The same pass measures how deep the text's arrays and objects
 * nest, with no recursion; brackets inside strings (text such as `"Watch [Episode 2]"`, escaped quotes included)
 * are not counted. Answers are a few KB (at most 5 MB, see `ApiClient`) and parsed off the main thread.
 */
internal object JsonParser {
    /**
     * The deepest arrays and objects may nest: the iOS JSON decoder's limit, so both SDKs refuse the same answers
     * (the shared corpus's `deep-nesting-*` cases). A real answer nests about 6 deep; one past 512 is not the
     * server's.
     */
    const val MAX_DEPTH = 512

    /** What ends a bare token: a structural character, white space or a quote. */
    private const val TOKEN_ENDS = "{}[],: \t\n\r\""

    /** `\uXXXX`: the backslash, the `u` and four hex digits. */
    private const val UNICODE_ESCAPE = 6
    private const val HEX = 16

    /** How much of a refused key or token the error message shows. */
    private const val KEY_SHOWN = 40

    /** A bare token JSON allows: a literal, or a number in JSON's own grammar (no leading zero, no `+`, no `NaN`). */
    private val bareToken = Regex("true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** [text] parsed. Throws for text that is not strict JSON, has a key twice in one object, or nests deeper than [MAX_DEPTH] (never parsed then). */
    fun parse(text: String): JsonElement {
        validate(text)
        return Json.parseToJsonElement(text)
    }

    /** One open object or array while [validate] reads: an object's keys so far, and whether a string now is a key. */
    private class Level(val keys: HashSet<String>?) {
        var expectsKey = keys != null
    }

    /**
     * One pass over [text], no recursion: the nesting depth, every bare token, and each object's keys. The
     * structure itself (commas, colons, brackets that match) is left to the parser after it.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // one character loop, one branch per kind of character
    private fun validate(text: String) {
        val levels = ArrayDeque<Level>()
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> {
                    val end = stringEnd(text, index)
                    val level = levels.lastOrNull()
                    if (level != null && level.expectsKey) {
                        level.expectsKey = false
                        val key = unescape(text, index + 1, end)
                        require(level.keys!!.add(key)) { "the key \"${key.take(KEY_SHOWN)}\" twice in one object" }
                    }
                    index = end + 1
                    continue
                }
                '{', '[' -> {
                    levels.addLast(Level(if (char == '{') HashSet() else null))
                    require(levels.size <= MAX_DEPTH) { "nested deeper than $MAX_DEPTH levels" }
                }
                '}', ']' -> levels.removeLastOrNull()
                ',' -> levels.lastOrNull()?.let { it.expectsKey = it.keys != null }
                ':', ' ', '\t', '\n', '\r' -> Unit
                else -> {
                    var end = index
                    while (end < text.length && text[end] !in TOKEN_ENDS) end++
                    val token = text.substring(index, end)
                    require(bareToken.matches(token)) { "not JSON: ${token.take(KEY_SHOWN)}" }
                    index = end
                    continue
                }
            }
            index++
        }
    }

    /**
     * The index of the quote that closes the string opened at [start]; the text's end for one never closed (the
     * parser refuses it). Throws for a raw control character (U+0000 to U+001F) inside: JSON allows them only
     * escaped (`\\n`), kotlinx's parser takes them, iOS's decoder refuses them.
     */
    private fun stringEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            require(char >= ' ') { "a raw control character (U+%04X) in a string".format(char.code) }
            when (char) {
                '\\' -> index++
                '"' -> return index
            }
            index++
        }
        return text.length
    }

    /** A key's text between [start] and [end], its escapes read, so `"a"` and `"\u0061"` are the same key. */
    private fun unescape(text: String, start: Int, end: Int): String {
        if (text.indexOf('\\', start).let { it == -1 || it >= end }) return text.substring(start, end)
        val out = StringBuilder()
        var index = start
        while (index < end) {
            val char = text[index]
            if (char != '\\' || index + 1 >= end) {
                out.append(char)
                index++
                continue
            }
            when (val escaped = text[index + 1]) {
                'u' -> {
                    val code = text.substring(index + 2, minOf(index + UNICODE_ESCAPE, end)).toIntOrNull(HEX)
                    require(code != null) { "a broken \\u escape in a key" }
                    out.append(code.toChar())
                    index += UNICODE_ESCAPE
                    continue
                }
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(escaped)
            }
            index += 2
        }
        return out.toString()
    }
}

// Shared by the two answers' decoders, `ConfigResponse` and `AdsResponse`.

/** Stands in for a `disabledReason` the server left out or sent malformed. */
internal const val UNKNOWN_REASON = "unknown"

/** The whole answer is refused: an [ApiException.Decoding] that says why. */
internal fun invalid(reason: String): Nothing = throw ApiException.Decoding(IllegalArgumentException(reason))
