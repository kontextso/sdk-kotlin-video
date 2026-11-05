package so.kontext.video.internal.diagnostics

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.putIfNotNull
import so.kontext.video.internal.payloads.SdkInfo
import so.kontext.video.internal.platform.Environment
import kotlin.math.abs

/**
 * Sends the SDK's debug events to `POST /debug`, only when `/config` asked for it (`reportDebug`). Events are
 * buffered and sent in batches, at most one request per [intervalMillis]. Small facts only: names, ad ids,
 * levels, and short string/number/boolean state. Never the advertising id, the email, the user id, consent
 * strings, request or response bodies. Every event is also one `debug` line in Logcat, and is recorded in
 * [EventHistory] for the history of an error report. Best effort: a batch that fails is lost, never retried.
 */
internal object DebugReporter {
    enum class Level { INFO, WARNING, ERROR }

    var intervalMillis = 100L
    const val MAX_BUFFERED = 500

    /** 2^53: every whole Double below it is exact as a Long. */
    private const val WHOLE_LIMIT = 9_007_199_254_740_992.0

    /** What `/config` said (`reportDebug`), read from the SDK's state: off until it answers. */
    val isEnabled: Boolean get() = Sdk.state.isDebugReportingOn

    private val buffer = mutableListOf<JsonObject>()
    private var flushJob: Job? = null

    fun event(name: String, adId: String? = null, level: Level? = null, state: Map<String, Any>? = null) {
        Logger.debug { line(name, adId, state) }
        val createdAt = ReportFormat.timestamp()
        EventHistory.record(createdAt, name, adId, state)
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

    /** `/config` answered (`Sdk.recordDebugReporting`): off drops what waits. */
    fun reportingChanged() {
        if (!isEnabled) buffer.clear()
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
        buffer.clear()
        EventHistory.clear()
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
}
