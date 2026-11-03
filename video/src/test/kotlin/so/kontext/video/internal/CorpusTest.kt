package so.kontext.video.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import so.kontext.video.AdFormat
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.parseAnswer
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.payloads.ConfigResponse
import java.io.File

/**
 * The shared corpus of malformed server answers (`corpus/`, a copy of the `video-sdk-corpus` repo; its README has
 * the format): every case through the decoder production uses, against the outcome both SDKs must produce. The
 * Swift SDK runs the same files. A case is a JSON file; nothing here changes to add one.
 */
class CorpusTest {
    private val root = File(checkNotNull(javaClass.classLoader?.getResource("corpus")) { "the corpus copy is missing" }.toURI())

    @Test fun theCorpusIsHere() {
        assertTrue(File(root, "VERSION").readText().isNotBlank())
        assertTrue(cases("ads").size > 1 && cases("config").size > 1, "the corpus copy is missing or empty")
    }

    @TestFactory fun adsAnswers(): List<DynamicTest> = cases("ads").map { file ->
        DynamicTest.dynamicTest("ads/${file.nameWithoutExtension}") { check(file) { outcome(AdsResponse.parse(it)) } }
    }

    @TestFactory fun configAnswers(): List<DynamicTest> = cases("config").map { file ->
        DynamicTest.dynamicTest("config/${file.nameWithoutExtension}") {
            check(file) { answer ->
                when (val config = ConfigResponse.parse(answer)) {
                    is ConfigResponse.Enabled -> mapOf("result" to "enabled", "reportDebug" to config.reportDebug, "reportErrors" to config.reportErrors)
                    is ConfigResponse.Disabled -> mapOf("result" to "disabled", "disabledReason" to config.reason)
                }
            }
        }
    }

    /** One route's cases by name; `*.raw.json` are answers a case points to, not cases. */
    private fun cases(route: String): List<File> =
        File(root, route).listFiles { file -> file.name.endsWith(".json") && !file.name.endsWith(".raw.json") }.orEmpty().sortedBy { it.name }

    /**
     * Parses the answer as `ApiClient` does and decodes it with [decode] (a decoding failure is `malformed`), then
     * compares with `expect`: every key it names, `ads` per format and per fact listed, `decodeReports` in any
     * order. A known difference between the SDKs is skipped: the corpus README says who decides it.
     */
    private fun check(file: File, decode: (JsonElement?) -> Map<String, Any?>) {
        val spec = Json.parseToJsonElement(file.readText()).jsonObject
        val name = "${file.parentFile?.name}/${file.nameWithoutExtension}"
        val difference = spec["knownDifference"]?.jsonPrimitive?.contentOrNull
        assumeTrue(difference == null) { "$name skipped, a known difference between the SDKs: $difference" }
        val actual = try {
            decode(answer(file, spec))
        } catch (_: ApiException.Decoding) {
            mapOf("result" to "malformed")
        }
        compare(name, spec.getValue("expect").jsonObject, actual)
    }

    /** The answer as `ApiClient` hands it over: the raw file's text or the inline value, with the generated parts. */
    private fun answer(file: File, spec: JsonObject): JsonElement? {
        val raw = spec["responseFile"]?.jsonPrimitive?.content
        val text = if (raw != null) File(file.parentFile, raw).readText() else generated(spec).toString()
        return parseAnswer(text)
    }

    private fun generated(spec: JsonObject): JsonElement =
        (spec["generate"]?.jsonArray ?: JsonArray(emptyList())).fold(spec.getValue("response")) { response, step ->
            val count = step.jsonObject.getValue("count").jsonPrimitive.int
            val repeated = step.jsonObject.getValue("repeat")
            val value = if (repeated is JsonPrimitive && repeated.isString) JsonPrimitive(repeated.content.repeat(count)) else JsonArray(List(count) { repeated })
            setting(value, step.jsonObject.getValue("path").jsonArray, response)
        }

    /** [root] with [value] at [path] (object keys and array indexes). */
    private fun setting(value: JsonElement, path: List<JsonElement>, root: JsonElement): JsonElement {
        val step = path.firstOrNull()?.jsonPrimitive ?: return value
        val rest = path.drop(1)
        return when {
            step.isString && root is JsonObject -> JsonObject(root + (step.content to setting(value, rest, root[step.content] ?: JsonNull)))
            step.intOrNull != null && root is JsonArray && step.int in root.indices ->
                JsonArray(root.mapIndexed { index, element -> if (index == step.int) setting(value, rest, element) else element })
            else -> root
        }
    }

    /** The answer as `VideoAds` takes it: each format's first ad, its no-fill reason, or nothing, and the error report's detail per dropped ad. */
    private fun outcome(response: AdsResponse): Map<String, Any?> {
        if (response is AdsResponse.Disabled) return mapOf("result" to "disabled", "disabledReason" to response.reason)
        val results = (response as AdsResponse.Formats).results
        val formats = mutableMapOf<String, String>()
        val ads = mutableMapOf<String, Map<String, Any?>>()
        for (format in AdFormat.entries) {
            val result = response.result(format)
            val ad = result?.ads?.firstOrNull()
            formats[format.wireName] = when {
                result == null -> "absent"
                ad == null -> "noFill:${result.noFillReason}"
                else -> "ad:${ad.id}"
            }
            if (ad != null) {
                ads[format.wireName] = mapOf(
                    "trackingCount" to ad.tracking.size.toLong(),
                    "durationMillis" to ad.durationMillis,
                    "skipOffsetMillis" to ad.skipOffsetMillis,
                    "sizesCount" to ad.sizes.size.toLong(),
                )
            }
        }
        val reports = results.flatMap { result -> result.dropped.map { "${result.format.wireName}:${it.path}" } }
        return mapOf("result" to "formats", "formats" to formats, "decodeReports" to reports, "ads" to ads)
    }

    private fun compare(name: String, expect: JsonObject, actual: Map<String, Any?>) {
        for ((key, expected) in expect) {
            when (key) {
                "decodeReports" -> assertEquals(
                    expected.jsonArray.map { it.jsonPrimitive.content }.sorted(),
                    (actual[key] as? List<*>).orEmpty().map { it.toString() }.sorted(),
                    "$name: decodeReports",
                )
                "formats" -> compareFormats(name, expected.jsonObject, actual[key] as? Map<*, *>)
                "ads" -> compareAds(name, expected.jsonObject, actual[key] as? Map<*, *>)
                else -> assertEquals(scalar(expected), actual[key], "$name: $key")
            }
        }
    }

    private fun compareFormats(name: String, expected: JsonObject, actual: Map<*, *>?) {
        for ((format, outcome) in expected) {
            val got = actual?.get(format) as? String
            val wanted = outcome.jsonPrimitive.content
            // `ad` alone: an ad, whatever its id (a generated one).
            val matches = if (wanted == "ad") got?.startsWith("ad:") == true else got == wanted
            assertTrue(matches, "$name: $format is $got, expected $wanted")
        }
    }

    private fun compareAds(name: String, expected: JsonObject, actual: Map<*, *>?) {
        for ((format, facts) in expected) {
            val got = actual?.get(format) as? Map<*, *>
            for ((fact, value) in facts.jsonObject) assertEquals(scalar(value), got?.get(fact), "$name: $format.$fact")
        }
    }

    /** A JSON scalar as the outcome holds it: a whole number as `Long`, `null` as `null`. */
    private fun scalar(element: JsonElement): Any? {
        val primitive = element as? JsonPrimitive ?: return element
        return when {
            primitive is JsonNull -> null
            primitive.isString -> primitive.content
            primitive.booleanOrNull != null -> primitive.booleanOrNull
            else -> primitive.content.toLongOrNull() ?: primitive.doubleOrNull
        }
    }
}
