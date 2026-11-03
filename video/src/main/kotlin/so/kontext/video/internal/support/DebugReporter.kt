package so.kontext.video.internal.support

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.putIfNotNull
import so.kontext.video.internal.payloads.SdkInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Sends the SDK's debug events to `POST /debug`, only when `/config` asked for it (`reportDebug`). Events are
 * buffered and sent in batches, at most one request per [intervalMillis]. Small facts only: names, ad ids,
 * levels, and short string/number/boolean state. Never the advertising id, the email, the user id, consent
 * strings, request or response bodies. Every event is also one `debug` line in Logcat, and the last
 * [HISTORY_LENGTH] are kept for the history of an error report.
 */
internal object DebugReporter {
    enum class Level { INFO, WARNING, ERROR }

    /** One event in the history an error report carries. */
    class Moment(val createdAt: String, val name: String, val adId: String?, val state: Map<String, Any>?)

    var intervalMillis = 100L
    const val MAX_BUFFERED = 500
    const val HISTORY_LENGTH = 30

    /** 2^53: every whole Double below it is exact as a Long. */
    private const val WHOLE_LIMIT = 9_007_199_254_740_992.0

    /** The state keys an error report's history may carry; anything else stays on the device. */
    val historyKeys = setOf("held", "playhead", "format", "filled", "reason", "enabled", "code", "durationMs", "step", "domain")

    var isEnabled = false
        set(value) {
            field = value
            if (!value) buffer.clear()
        }

    private val buffer = mutableListOf<JsonObject>()
    private val history = ArrayDeque<Moment>()
    private var flushJob: Job? = null

    fun event(name: String, adId: String? = null, level: Level? = null, state: Map<String, Any>? = null) {
        Logger.debug { line(name, adId, state) }
        val createdAt = timestamp()
        history.addLast(Moment(createdAt, name, adId, state))
        while (history.size > HISTORY_LENGTH) history.removeFirst()
        if (!isEnabled) return
        buffer += buildJsonObject {
            put("createdAt", createdAt)
            put("name", name)
            putIfNotNull("adId", adId)
            putIfNotNull("level", level?.name?.lowercase())
            putIfNotNull("state", state)
        }
        while (buffer.size > MAX_BUFFERED) buffer.removeAt(0)
        scheduleFlush()
    }

    /** The recent events, reduced for an error report: listed state keys only, strings and ids capped. */
    fun recentHistory(): List<Moment> = history.map { moment ->
        val state = moment.state?.filterKeys { it in historyKeys }?.mapValues { (_, value) ->
            if (value is String) ErrorReporter.capped(value) else value
        }
        Moment(moment.createdAt, ErrorReporter.capped(moment.name), moment.adId?.let(ErrorReporter::capped), state?.takeIf { it.isNotEmpty() })
    }

    /** `ad.loaded ad=f1a2b3c4 durationMs=842`, the event as Logcat shows it. */
    fun line(name: String, adId: String?, state: Map<String, Any>?): String = buildList {
        add(name)
        adId?.let { add("ad=${it.take(8)}") }
        state?.toSortedMap()?.forEach { (key, value) -> add("$key=${describe(value)}") }
    }.joinToString(" ")

    fun resetForTesting() {
        flushJob?.cancel()
        flushJob = null
        isEnabled = false
        buffer.clear()
        history.clear()
        intervalMillis = 100L
    }

    private fun describe(value: Any): String = when (value) {
        // A whole number prints as one only within the range a Long holds exactly; beyond it `toLong` would clamp.
        is Double -> if (value % 1.0 == 0.0 && abs(value) < WHOLE_LIMIT) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = Environment.scope.launch {
            delay(intervalMillis)
            flush()
            flushJob = null
            if (isEnabled && buffer.isNotEmpty()) scheduleFlush()
        }
    }

    private suspend fun flush() {
        val configuration = Sdk.configuration ?: return
        val context = Environment.context ?: return
        if (!isEnabled || buffer.isEmpty()) return
        val events = buffer.toList()
        buffer.clear()
        val body = buildJsonObject {
            put("publisherToken", configuration.publisherToken)
            put("installId", DeviceInfo.installId(context))
            put("sdk", SdkInfo.json())
            put("events", JsonArray(events))
        }
        try {
            ApiClient(configuration.adServerUrl).post("debug", body)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Debug reporting never affects the SDK; a lost batch is lost.
            Logger.debug { "debug batch of ${events.size} not delivered: ${error.message}" }
        }
    }

    /** `2026-10-21T18:00:00.482Z`. */
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    /** A state value as JSON. */
    fun json(value: Any): JsonPrimitive = when (value) {
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
