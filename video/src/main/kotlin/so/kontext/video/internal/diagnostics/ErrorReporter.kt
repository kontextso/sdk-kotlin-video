package so.kontext.video.internal.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.SdkInfo
import so.kontext.video.internal.platform.Environment
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Sends the SDK's faults to `POST /v2/error`: something Kontext should fix (a server or contract problem, a
 * creative or frame page that broke, a show the system refused, an integration mistake). Never an
 * outcome the app handles (no fill, destroyed, cancelled, disabled).
 *
 * Off until `/config` says `reportErrors: true`. Before it answers, reports wait in memory and leave once it
 * turns them on, or are dropped. Delivery is bounded and best effort: at most `ReportQueue.MAX_BUFFERED` reports
 * wait (the oldest go, and the next batch says how many); one batch at a time, at most `ReportQueue.MAX_BATCH`; a batch that
 * fails is retried unchanged after a doubling backoff with jitter; `410` ends reporting for this launch, any
 * other `4xx` but `429` drops that batch. Nothing is stored on disk. Each report carries codes and ids, never
 * free text from a system error, the advertising id, the user id, the email, consent strings, bodies or URLs.
 */
internal object ErrorReporter {
    private enum class Mode { PENDING, ON, OFF }

    var intervalMillis = 100L
    var retryDelayMillis = 30_000L
    var maxRetryDelayMillis = 300_000L

    /**
     * The server answered a batch with `410`: reporting ends for this launch, whatever `/config` said. The
     * reporter's own fact, from its own endpoint, not the SDK's state.
     */
    private var wasStoppedByServer = false

    /** Pending until `/config` answers, then what it said (`SdkState.isErrorReportingOn`), unless the server stopped it. */
    private val mode: Mode
        get() = when {
            wasStoppedByServer -> Mode.OFF
            else -> when (Sdk.state.isErrorReportingOn) {
                null -> Mode.PENDING
                true -> Mode.ON
                false -> Mode.OFF
            }
        }
    private val queue = ReportQueue()

    /** The batch being delivered: its finished body, sent unchanged until the server answers it. */
    private var pendingBatch: ReportQueue.Batch? = null
    private var failures = 0
    private var flushJob: Job? = null

    /** Reports waiting, for tests. */
    val bufferedCount: Int get() = queue.size

    /** The encoded sizes of the reports waiting, for tests. */
    val bufferedSizes: List<Int> get() = queue.sizes

    /** The codes of the reports waiting, for tests. */
    val bufferedCodes: List<String> get() = queue.reports.map { it["code"].toString().trim('"') }

    /** The details of the reports waiting (empty without one), for tests. */
    val bufferedDetails: List<String> get() = queue.reports.map { it["detail"]?.toString()?.trim('"').orEmpty() }

    /**
     * `/config` answered (`Sdk.recordErrorReporting`): reports on send what waited, reports off drop it for this
     * launch.
     */
    fun reportingChanged() {
        when (mode) {
            Mode.ON -> if (!queue.isEmpty()) scheduleFlush(intervalMillis)
            Mode.OFF -> dropAll()
            Mode.PENDING -> Unit
        }
    }

    fun report(
        code: ErrorReport.Code,
        stage: ErrorReport.Stage,
        requestId: String? = null,
        sessionId: String? = null,
        adId: String? = null,
        vastCode: Int? = null,
        httpStatus: Int? = null,
        underlying: ErrorReport.Underlying? = null,
        detail: String? = null,
        recovery: ErrorReport.Recovery? = null,
    ) {
        if (mode == Mode.OFF) return
        val report = ErrorReport.json(code, stage, requestId, sessionId, adId, vastCode, httpStatus, underlying, detail, recovery)
        val adPart = adId?.let { " ad=" + it.take(8) } ?: ""
        Logger.debug { "error report ${code.wire}$adPart" }
        if (!queue.add(report)) return
        if (mode == Mode.ON) scheduleFlush(intervalMillis)
    }

    /** A failed ads or `/config` request. */
    fun report(
        code: ErrorReport.Code,
        stage: ErrorReport.Stage,
        requestId: String,
        sessionId: String? = null,
        failure: ErrorReport.FailedRequest,
        recovery: ErrorReport.Recovery? = null,
    ) {
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

    fun resetForTesting() {
        flushJob?.cancel()
        flushJob = null
        wasStoppedByServer = false
        queue.clear()
        pendingBatch = null
        failures = 0
        intervalMillis = 100L
        retryDelayMillis = 30_000L
        maxRetryDelayMillis = 300_000L
    }

    private fun dropAll() {
        flushJob?.cancel()
        flushJob = null
        queue.clear()
        pendingBatch = null
    }

    private fun scheduleFlush(delayMillis: Long) {
        if (flushJob != null) return
        flushJob = Environment.scope.launch {
            delay(delayMillis)
            val delivered = flush()
            flushJob = null
            if (mode != Mode.ON || (pendingBatch == null && queue.isEmpty())) return@launch
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
                wasStoppedByServer = true
                dropAll()
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

    /** The next batch from the queue, in this launch's envelope; it becomes the pending one. `null` when nothing is left. */
    private fun makeBatch(configuration: so.kontext.video.KontextVideo.Configuration, context: android.content.Context): ReportQueue.Batch? {
        val batchId = UUID.randomUUID().toString()
        val os = DeviceInfo.os(context)
        return queue.makeBatch { errors, dropped ->
            buildJsonObject {
                put("batchId", batchId)
                put("publisherToken", configuration.publisherToken)
                put("installId", DeviceInfo.installId(context))
                put("sdk", SdkInfo.json())
                put("app", DeviceInfo.app(context))
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
        }?.also { pendingBatch = it }
    }
}
