package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * How the SDK reads JSON fields, and why.
 *
 * Answers are parsed into a JSON tree ([JsonParser.parse]) and read field by field with the lenient readers
 * below, not decoded into `@Serializable` classes:
 * - Leniency where it matters: a missing or wrongly typed field reads as `null`, so one malformed ad can be dropped
 *   while the rest of the answer is used (see `AdsResponse`), where typed decoding fails all or nothing.
 * - Nothing for the app's R8 to keep: no generated serializers, nothing reached by reflection, which is why
 *   `consumer-proguard-rules.pro` has no rules.
 */

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
