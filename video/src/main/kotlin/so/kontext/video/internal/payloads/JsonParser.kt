package so.kontext.video.internal.payloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Parses JSON into a tree (`Json.parseToJsonElement`, kotlinx.serialization's equivalent of `JSON.parse`), with
 * a guard on how deep it nests. Every JSON the SDK reads from outside goes through it: the server's answers
 * (`ApiClient`, which turns a failure into `ApiException.Decoding`), its error bodies (`ErrorResponse`) and the
 * ad page's messages (`FrameMessage`).
 *
 * Why the guard: the parser is recursive, one call per level. A text like `[[[[…]]]]` nested 100 000 deep would
 * exhaust the stack and throw `StackOverflowError`, an `Error` that no `catch (Exception)` stops, crashing the
 * app from an ad request or a page message. So before parsing, [parse] measures how deep the text's arrays and
 * objects nest: one pass over the characters with no recursion; brackets inside strings (text such as
 * `"Watch [Episode 2]"`, escaped quotes included) are not counted, only the JSON's own structure is. Answers are a
 * few KB (at most 5 MB, see `ApiClient`) and parsed off the main thread: well under a millisecond.
 */
internal object JsonParser {
    /**
     * The deepest arrays and objects may nest: the iOS JSON decoder's limit, so both SDKs refuse the same answers
     * (the shared corpus's `deep-nesting-*` cases). A real answer nests about 6 deep; one past 512 is not the
     * server's.
     */
    const val MAX_DEPTH = 512

    /** [text] parsed. Throws for text that is not JSON, or nested deeper than [MAX_DEPTH] (never parsed then). */
    fun parse(text: String): JsonElement {
        require(depth(text) <= MAX_DEPTH) { "nested deeper than $MAX_DEPTH levels" }
        return Json.parseToJsonElement(text)
    }

    /** How deep [text]'s arrays and objects nest, brackets inside strings not counted; read before parsing, so a deep text costs no stack. */
    private fun depth(text: String): Int {
        var depth = 0
        var deepest = 0
        var isInString = false
        var isEscaped = false
        for (char in text) {
            when {
                isEscaped -> isEscaped = false
                isInString && char == '\\' -> isEscaped = true
                char == '"' -> isInString = !isInString
                isInString -> Unit
                char == '[' || char == '{' -> deepest = maxOf(deepest, ++depth)
                char == ']' || char == '}' -> depth--
            }
        }
        return deepest
    }
}
