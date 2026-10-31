package so.kontext.video.internal.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.putIfNotNull
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Sends the SDK's faults to `POST /v2/error`: something Kontext should fix (a server or contract problem, a
 * creative or frame page that broke, a presentation the system refused, an integration mistake). Never an
 * outcome the app handles (no fill, destroyed, cancelled, disabled).
 *
 * Off until `/config` says `reportErrors: true`. Before it answers, reports wait in memory and leave once it
 * turns them on, or are dropped. Delivery is bounded and best effort: at most [MAX_BUFFERED] reports wait
 * (the oldest go, and the next batch says how many); one batch at a time, at most [MAX_BATCH]; a batch that
 * fails is retried unchanged after a doubling backoff with jitter; `410` ends reporting for this launch, any
 * other `4xx` but `429` drops that batch. Nothing is stored on disk. Each report carries codes and ids, never
 * free text from a system error, the advertising id, the user id, the email, consent strings, bodies or URLs.
 */
internal object ErrorReporter {
    /** What failed. Stable: the server groups by it. */
    enum class Code(val wire: String) {
        CONFIG_FAILED("config.failed"),
        ADS_REQUEST_FAILED("ads.request_failed"),
        ADS_DECODE_FAILED("ads.decode_failed"),
        CREATIVE_FAILED("ad.creative_failed"),
        LOAD_TIMEOUT("ad.load_timeout"),
        FRAME_LOAD_FAILED("frame.load_failed"),
        PROCESS_TERMINATED("frame.process_terminated"),
        UNRESPONSIVE("frame.unresponsive"),
        PROTOCOL_VIOLATION("frame.protocol_violation"),
        PRESENTATION_FAILED("ad.presentation_failed"),
        DISMISSAL_TIMED_OUT("ad.dismissal_timed_out"),
        EMPTY_TOKEN("integration.empty_token"),
        BAD_SERVER_URL("integration.bad_server_url"),
        NOT_INITIALIZED("integration.not_initialized"),
        BAD_CONTENT("integration.bad_content"),
    }

    /** Where in the SDK it failed. */
    enum class Stage { CONFIG, REQUEST, LOAD, PRESENTATION, PLAYBACK, INTEGRATION }

    /** What the SDK did about it. */
    enum class Recovery(val wire: String) {
        HELD_ADS_KEPT("held_ads_kept"),
        NO_AD("no_ad"),
        AD_ENDED_FAILED("ad_ended_failed"),
        AD_DROPPED_BEFORE_SHOW("ad_dropped_before_show"),

        /** The system never finished; `onAdEnded` was delivered anyway. */
        DID_END_DELIVERED("did_end_delivered"),
    }

    /** A request's failure as the report's fields: an HTTP status, or the exception's class with `network`. */
    class RequestFailure(error: Throwable) {
        val httpStatus: Int? = (error as? ApiException.Http)?.status
        val underlying: String? = when (error) {
            is ApiException.Http -> null
            is ApiException.Transport, is ApiException.Decoding -> error.cause?.javaClass?.name
            else -> error.javaClass.name
        }
        val detail: String? = if (error is ApiException.Transport) "network" else null
    }

    private enum class Mode { PENDING, ON, OFF }

    var intervalMillis = 100L
    var retryDelayMillis = 30_000L
    var maxRetryDelayMillis = 300_000L
    const val MAX_BUFFERED = 200
    const val MAX_BATCH = 50
    const val MAX_BUFFERED_BYTES = 256 * 1024
    const val MAX_BATCH_BYTES = 64 * 1024

    /** UTF-8 bytes kept of every string field. */
    const val MAX_FIELD_LENGTH = 64

    private var mode = Mode.PENDING
    private val buffer = ArrayDeque<Pair<JsonObject, Int>>()
    private var bufferedBytes = 0
    private var dropped = 0

    /** The batch being delivered: its finished body, sent unchanged until the server answers it. */
    private var pendingBatch: Batch? = null

    private class Batch(val body: JsonObject, val count: Int)
    private var failures = 0
    private var flushJob: Job? = null

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

    /** `/config` answered: `reportErrors` turns the reports on, anything else drops them for this launch. */
    fun configure(enabled: Boolean) {
        if (mode == Mode.OFF) return
        if (enabled) {
            mode = Mode.ON
            if (buffer.isNotEmpty()) scheduleFlush(intervalMillis)
        } else {
            turnOff()
        }
    }

    fun report(
        code: Code,
        stage: Stage,
        requestId: String? = null,
        sessionId: String? = null,
        adId: String? = null,
        vastCode: Int? = null,
        httpStatus: Int? = null,
        underlying: String? = null,
        detail: String? = null,
        recovery: Recovery? = null,
    ) {
        if (mode == Mode.OFF) return
        val report = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("createdAt", DebugReporter.timestamp())
            put("code", code.wire)
            put("stage", stage.name.lowercase())
            putIfNotNull("requestId", requestId?.let(::capped))
            putIfNotNull("sessionId", sessionId?.let(::capped))
            putIfNotNull("adId", adId?.let(::capped))
            putIfNotNull("vastCode", vastCode)
            putIfNotNull("httpStatus", httpStatus)
            underlying?.let { put("underlying", buildJsonObject { put("domain", capped(it)) }) }
            putIfNotNull("detail", detail?.let(::capped))
            putIfNotNull("recovery", recovery?.wire)
            put(
                "history",
                JsonArray(
                    DebugReporter.recentHistory().map { moment ->
                        buildJsonObject {
                            put("createdAt", moment.createdAt)
                            put("name", moment.name)
                            putIfNotNull("adId", moment.adId)
                            moment.state?.let { state -> put("state", JsonObject(state.mapValues { DebugReporter.json(it.value) })) }
                        }
                    },
                ),
            )
        }
        Logger.debug { "error report ${code.wire}${adId?.let { " ad=${it.take(8)}" } ?: ""}" }
        val size = report.toString().toByteArray().size
        if (size > MAX_BATCH_BYTES) {
            dropped += 1
            return
        }
        buffer.addLast(report to size)
        bufferedBytes += size
        while (buffer.size > MAX_BUFFERED || (bufferedBytes > MAX_BUFFERED_BYTES && buffer.size > 1)) {
            bufferedBytes -= buffer.removeFirst().second
            dropped += 1
        }
        if (mode == Mode.ON) scheduleFlush(intervalMillis)
    }

    /** A failed ads or `/config` request. */
    fun report(code: Code, stage: Stage, requestId: String, sessionId: String? = null, failure: RequestFailure, recovery: Recovery? = null) {
        report(
            code,
            stage,
            requestId = requestId,
            sessionId = sessionId,
            httpStatus = failure.httpStatus,
            underlying = failure.underlying,
            detail = failure.detail,
            recovery = recovery,
        )
    }

    /** Reports waiting, for tests. */
    val bufferedCount: Int get() = buffer.size

    /** The encoded sizes of the reports waiting, for tests. */
    val bufferedSizes: List<Int> get() = buffer.map { it.second }

    /** The codes of the reports waiting, for tests. */
    val bufferedCodes: List<String> get() = buffer.map { it.first["code"].toString().trim('"') }

    fun resetForTesting() {
        flushJob?.cancel()
        flushJob = null
        mode = Mode.PENDING
        buffer.clear()
        bufferedBytes = 0
        dropped = 0
        pendingBatch = null
        failures = 0
        intervalMillis = 100L
        retryDelayMillis = 30_000L
        maxRetryDelayMillis = 300_000L
    }

    private fun turnOff() {
        mode = Mode.OFF
        flushJob?.cancel()
        flushJob = null
        buffer.clear()
        bufferedBytes = 0
        dropped = 0
        pendingBatch = null
    }

    private fun scheduleFlush(delayMillis: Long) {
        if (flushJob != null) return
        flushJob = Environment.scope.launch {
            delay(delayMillis)
            val delivered = flush()
            flushJob = null
            if (mode != Mode.ON || (pendingBatch == null && buffer.isEmpty())) return@launch
            scheduleFlush(if (delivered) intervalMillis else nextRetryDelay())
        }
    }

    private fun nextRetryDelay(): Long {
        val base = min(maxRetryDelayMillis.toDouble(), retryDelayMillis * 2.0.pow(maxOf(0, failures - 1)))
        return (base * Random.nextDouble(0.8, 1.2)).toLong()
    }

    // Batch, send, and the server's answers each decide one thing.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun flush(): Boolean {
        val configuration = Sdk.configuration ?: return true
        val context = Environment.context ?: return true
        if (mode != Mode.ON) return true
        val batch = pendingBatch ?: makeBatch(configuration, context) ?: return true
        return try {
            ApiClient(configuration.adServerUrl).post("error", batch.body)
            pendingBatch = null
            failures = 0
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException.Http) {
            if (error.status == 410) {
                Logger.debug { "error reports turned off by the server" }
                turnOff()
                true
            } else if (error.status in 400..499 && error.status != 429) {
                Logger.debug { "error batch of ${batch.count} refused with ${error.status}, dropped" }
                pendingBatch = null
                true
            } else {
                failures += 1
                false
            }
        } catch (error: Exception) {
            Logger.debug { "error batch of ${batch.count} not delivered: ${error.message}" }
            failures += 1
            false
        }
    }

    /**
     * The next batch from the buffer: the oldest reports whose whole body, envelope and separators included,
     * stays within [MAX_BATCH_BYTES]; the rest wait for the next one. A report that does not fit even alone is
     * dropped and counted. `null` when nothing is left to send.
     */
    private fun makeBatch(configuration: so.kontext.video.Configuration, context: android.content.Context): Batch? {
        val batchId = UUID.randomUUID().toString()
        val os = DeviceFacts.os(context)
        fun body(errors: List<JsonObject>, dropped: Int) = buildJsonObject {
            put("batchId", batchId)
            put("publisherToken", configuration.publisherToken)
            put("installId", DeviceFacts.installId(context))
            put("sdk", DeviceFacts.sdk())
            put("app", DeviceFacts.app(context))
            put(
                "os",
                buildJsonObject {
                    os["name"]?.let { put("name", it) }
                    os["version"]?.let { put("version", it) }
                },
            )
            put("dropped", dropped)
            put("errors", JsonArray(errors))
        }
        // The empty envelope, its `dropped` as large as it can get while this batch is made.
        var bytes = body(emptyList(), dropped + buffer.size).toString().toByteArray().size
        val reports = mutableListOf<JsonObject>()
        while (buffer.isNotEmpty() && reports.size < MAX_BATCH) {
            val (report, size) = buffer.first()
            val added = size + if (reports.isEmpty()) 0 else 1 // the comma before it
            if (bytes + added > MAX_BATCH_BYTES && reports.isNotEmpty()) break
            buffer.removeFirst()
            bufferedBytes -= size
            if (bytes + added > MAX_BATCH_BYTES) {
                dropped += 1
                continue
            }
            reports += report
            bytes += added
        }
        if (reports.isEmpty()) return null
        return Batch(body(reports, dropped), reports.size).also {
            dropped = 0
            pendingBatch = it
        }
    }
}
