package so.kontext.video.internal.diagnostics

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Configuration.LogLevel
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.payloads.ConfigResponse
import so.kontext.video.internal.payloads.SdkInfo
import so.kontext.video.internal.payloads.putIfNotNull
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState
import kotlin.math.abs

/**
 * Sends the SDK's debug events to `POST /debug`, only when `/config` asked for it (`reportDebug`): the structured
 * events (`ads.request`, `ad.loaded`, …) and, as the event `log`, every line [Logger] writes to Logcat, whatever
 * the Logcat level. Events are buffered and sent in batches, one every [intervalMillis] as `/config` set it (0:
 * no wait; what arrives while a request is in flight leaves together after it). The structured events carry
 * names, ids, levels and short string/number/boolean properties, never the advertising id, the email, the user
 * id, consent strings, request or response bodies. A Logcat line goes as written, uncapped, with every URL's
 * query removed (a filled macro can hold the advertising id); what else the SDK logs is what the server may
 * read, here and in an error report's history. Every structured event is also one `debug` line in Logcat, and
 * every event is recorded in [EventHistory] for the history of an error report. Best effort: a batch that fails
 * is lost, never retried.
 */
internal object DebugReporter {
    /** Absent means `info`. */
    enum class Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        ;

        companion object {
            /** The Logcat level as the event's; `null` for `NONE`, which no message has. */
            fun of(level: LogLevel): Level? = when (level) {
                LogLevel.NONE -> null
                LogLevel.ERROR -> ERROR
                LogLevel.WARNING -> WARNING
                LogLevel.INFO -> INFO
                LogLevel.DEBUG -> DEBUG
            }
        }
    }

    /** The wait before a batch leaves, as `/config` set it (`debugBatchInterval`); 0 waits for nothing but a request in flight. */
    var intervalMillis = ConfigResponse.DEFAULT_BATCH_INTERVAL_MILLIS
    const val MAX_BUFFERED = 500

    /** 2^53: every whole Double below it is exact as a Long. */
    private const val WHOLE_LIMIT = 9_007_199_254_740_992.0

    private val queryString = Regex("""(?i)\b([a-z][a-z0-9+.-]*:(?://)?[^\s?#]*)\?[^\s]*""")

    /** What `/config` said (`reportDebug`), read from the SDK's state: off until it answers. */
    val isEnabled: Boolean get() = SdkState.current.isDebugReportingOn

    /** [isEnabled], readable from any thread: [Logger] asks before it builds a line Logcat would not show. */
    @Volatile var forwardsConsole = false
        private set

    private val buffer = mutableListOf<JsonObject>()
    private var flushJob: Job? = null

    /** Records a structured event; it leaves with the next batch. Also one `debug` line in Logcat, not forwarded. */
    fun event(name: String, sessionId: String? = null, bidId: String? = null, level: Level? = null, properties: Map<String, Any>? = null) {
        Logger.console(LogLevel.DEBUG) { line(name, bidId, properties) }
        // Every string capped as the event is kept, as an error report's are: an ad id has no length limit on the
        // wire, and the buffer (500 events) and its batch must stay small.
        record(
            json(
                ReportFormat.timestamp(),
                ReportFormat.capped(name),
                sessionId,
                bidId?.let(ReportFormat::capped),
                level,
                properties?.mapValues { (_, value) -> if (value is String) ReportFormat.capped(value) else value },
            ),
        )
    }

    /**
     * A line [Logger] wrote, as the event `log` with the line as `message`, uncapped: Logcat's story of the install.
     * From any thread; the timestamp is the line's, the event is recorded on the SDK's scope.
     */
    fun forward(level: LogLevel, message: String) {
        val eventLevel = Level.of(level) ?: return
        val occurredAt = ReportFormat.timestamp()
        val text = withoutQueryStrings(message)
        Environment.scope.launch {
            record(json(occurredAt, "log", null, null, eventLevel, mapOf("message" to text)))
        }
    }

    /** `/config` answered (`SdkInitializer.recordDebugReporting`): off drops what waits. */
    fun reportingChanged() {
        val enabled = isEnabled
        forwardsConsole = enabled
        if (!enabled) buffer.clear()
    }

    /** `ad.loaded ad=f1a2b3c4 durationMs=842`, the event as Logcat shows it. */
    fun line(name: String, bidId: String?, properties: Map<String, Any>?): String = buildList {
        add(name)
        bidId?.let { add("ad=${it.take(8)}") }
        properties?.toSortedMap()?.forEach { (key, value) -> add("$key=${describe(value)}") }
    }.joinToString(" ")

    /** [text] with the query cut from every URL in it, whatever its scheme or case, `//` or not (a deep link such as `app:offer?x`): a filled macro can carry the advertising id. */
    fun withoutQueryStrings(text: String): String = text.replace(queryString, "$1")

    fun resetForTesting() {
        flushJob?.cancel()
        flushJob = null
        buffer.clear()
        EventHistory.clear()
        intervalMillis = ConfigResponse.DEFAULT_BATCH_INTERVAL_MILLIS
        forwardsConsole = false
    }

    private fun json(occurredAt: String, name: String, sessionId: String?, bidId: String?, level: Level?, properties: Map<String, Any>?): JsonObject =
        buildJsonObject {
            put("occurredAt", occurredAt)
            put("name", name)
            putIfNotNull("sessionId", sessionId)
            putIfNotNull("bidId", bidId)
            putIfNotNull("level", level?.name?.lowercase())
            properties?.let { values -> put("properties", JsonObject(values.mapValues { (_, value) -> ReportFormat.json(value) })) }
        }

    /** One event kept: in the history every error report carries, and, while `/config` asks for the debug log, in the next batch. */
    private fun record(event: JsonObject) {
        EventHistory.record(event)
        if (!isEnabled) return
        buffer += event
        while (buffer.size > MAX_BUFFERED) buffer.removeAt(0)
        scheduleFlush()
    }

    private fun describe(value: Any): String = when (value) {
        // A whole number prints as one only within the range a Long holds exactly; beyond it `toLong` would clamp.
        is Double -> if (value % 1.0 == 0.0 && abs(value) < WHOLE_LIMIT) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        // Kept lazy until it is the flush job: on an immediate dispatcher a flush that never suspends (interval 0,
        // nothing to send) would otherwise finish, clear `flushJob`, and then be stored in it, done, for good.
        val job = Environment.scope.launch(start = CoroutineStart.LAZY) {
            if (intervalMillis > 0) delay(intervalMillis)
            flush()
            flushJob = null
            if (isEnabled && buffer.isNotEmpty()) scheduleFlush()
        }
        flushJob = job
        job.start()
    }

    private suspend fun flush() {
        val configuration = SdkState.current.configuration ?: return
        val context = Environment.context ?: return
        if (!isEnabled || buffer.isEmpty()) return
        val events = buffer.toList()
        buffer.clear()
        try {
            // Built inside the boundary: the install id read may throw (a wrong-typed stored value).
            val body = buildJsonObject {
                put("publisherToken", configuration.publisherToken)
                put("installId", DeviceInfo.installId(context))
                put("sdk", SdkInfo.json())
                put("app", DeviceInfo.app(context))
                put("os", DeviceInfo.osForReports(context))
                put("events", JsonArray(events))
            }
            ApiClient(configuration.adServerUrl).post("debug", body, expectsAnswer = false)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Debug reporting never affects the SDK; a lost batch is lost. The line about it is not forwarded: it
            // would make the next batch, and fail the same way.
            Logger.console(LogLevel.DEBUG) { "debug batch of ${events.size} not delivered: ${error.message}" }
        }
    }
}
