package so.kontext.video.internal.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The deepest an answer's arrays and objects may nest: the iOS JSON decoder's limit, so both SDKs refuse the same
 * answers. No answer of the contract comes near it; one past it is not the server's.
 */
internal const val MAX_JSON_DEPTH = 512

/** A server answer parsed, or [ApiException.Decoding]: not JSON, or nested deeper than [MAX_JSON_DEPTH]. */
internal fun parseAnswer(text: String): JsonElement {
    if (depth(text) > MAX_JSON_DEPTH) throw ApiException.Decoding(IllegalArgumentException("nested deeper than $MAX_JSON_DEPTH levels"))
    return try {
        Json.parseToJsonElement(text)
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        throw ApiException.Decoding(error)
    }
}

/** How deep [text]'s arrays and objects nest, brackets inside strings not counted; read before parsing, so a deep answer costs no stack. */
private fun depth(text: String): Int {
    var depth = 0
    var deepest = 0
    var inString = false
    var escaped = false
    for (char in text) {
        when {
            escaped -> escaped = false
            inString && char == '\\' -> escaped = true
            char == '"' -> inString = !inString
            inString -> Unit
            char == '[' || char == '{' -> deepest = maxOf(deepest, ++depth)
            char == ']' || char == '}' -> depth--
        }
    }
    return deepest
}

// Lenient readers over parsed JSON: a missing, null or wrongly typed value is `null`.

internal fun JsonElement.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { primitive ->
    primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it % 1.0 == 0.0 && it in -9.0e15..9.0e15 }?.toLong()
}
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonObject.has(key: String): Boolean = this[key] != null && this[key] !is JsonNull

/** Writes a value only when it is present: absent fields are left out of every request. */
internal fun JsonObjectBuilder.putIfNotNull(key: String, value: Any?) {
    when (value) {
        null -> Unit
        is JsonElement -> put(key, value)
        is String -> put(key, JsonPrimitive(value))
        is Number -> put(key, JsonPrimitive(value))
        is Boolean -> put(key, JsonPrimitive(value))
        is List<*> -> put(key, JsonArray(value.map { item -> toJson(item) }))
        is Map<*, *> -> put(key, JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) }))
        else -> error("cannot encode ${value::class.simpleName}")
    }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> error("cannot encode ${value::class.simpleName}")
}
