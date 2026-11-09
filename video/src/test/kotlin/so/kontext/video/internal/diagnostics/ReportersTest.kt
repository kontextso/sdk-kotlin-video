package so.kontext.video.internal.diagnostics

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.platform.Environment
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** The error and debug reports, delivered to a local server: when they leave, in what batches, and what stops them. */
@RunWith(RobolectricTestRunner::class)
class ReportersTest {
    private val server = MockWebServer()
    private val received = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
    private val errorBodies = ConcurrentLinkedQueue<ByteArray>()

    @Volatile private var errorAnswers: MutableList<Int> = mutableListOf()

    /** The body `/v2/error` answers with, none by default. */
    @Volatile private var errorBody: String? = null

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val bytes = request.body.readByteArray()
                if (path == "/v2/error") errorBodies += bytes
                received += path to Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                val status = if (path == "/v2/error") synchronized(this) { errorAnswers.removeFirstOrNull() } ?: 204 else 204
                return MockResponse().setResponseCode(status).also { response -> errorBody?.takeIf { path == "/v2/error" }?.let(response::setBody) }
            }
        }
        server.start()
        Environment.context = ApplicationProvider.getApplicationContext()
        SdkInitializer.configureForTesting(Configuration("test-token") { adServerUrl = server.url("/").toString() })
        ErrorReporter.intervalMillis = 10
        ErrorReporter.retryDelayMillis = 50
        ErrorReporter.maxRetryDelayMillis = 100
        DebugReporter.intervalMillis = 10
    }

    @After fun stop() {
        SdkInitializer.resetForTesting()
        Environment.reset()
        server.shutdown()
    }

    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            // Robolectric's main-looper clock moves only when told: the flush delays run on it.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    /** Lets a few flush intervals pass, to show nothing leaves. */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(10)
        }
    }

    private fun errorBatches() = received.filter { it.first == "/v2/error" }.map { it.second }

    private fun report(code: ErrorReport.Code = ErrorReport.Code.CREATIVE_FAILED, detail: String = "x") =
        ErrorReporter.report(code, bidId = "ad-1", vastCode = 405, detail = detail)

    @Test fun reportsWaitForConfigAndLeaveTogetherWithTheirHistory() {
        DebugReporter.event("ad.shown", sessionId = "s-1", bidId = "ad-1", properties = mapOf("format" to "overlay", "secret" to "no"))
        report()
        report(ErrorReport.Code.LOAD_TIMEOUT)
        settle()
        assertTrue("nothing leaves before /config", errorBatches().isEmpty())
        SdkInitializer.recordErrorReporting(true)
        await { errorBatches().isNotEmpty() }
        val batch = errorBatches().single()
        assertEquals("test-token", batch["publisherToken"]!!.jsonPrimitive.content)
        assertNull("nothing the server ignores", batch["batchId"])
        assertNull(batch["dropped"])
        assertTrue("the app and the OS around the reports", batch["app"]!!.jsonObject.containsKey("bundleId") && batch["os"]!!.jsonObject.containsKey("name"))
        val errors = batch["errors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("ad.creative_failed", "ad.load_timeout"), errors.map { it["code"]!!.jsonPrimitive.content })
        assertEquals(405, errors[0]["vastCode"]!!.jsonPrimitive.int)
        val moment = errors[0]["history"]!!.jsonArray.single().jsonObject
        assertEquals("ad.shown", moment["name"]!!.jsonPrimitive.content)
        assertEquals("every property, as the debug event has it", setOf("format", "secret"), moment["properties"]!!.jsonObject.keys)
        assertEquals("a debug event, session and all", "s-1", moment["sessionId"]!!.jsonPrimitive.content)
    }

    @Test fun configWithoutReportsDropsThemForTheLaunch() {
        report()
        SdkInitializer.recordErrorReporting(false)
        report()
        SdkInitializer.recordErrorReporting(true) // off is final
        report()
        settle()
        assertTrue(errorBatches().isEmpty())
        assertEquals(0, ErrorReporter.bufferedCount)
    }

    @Test fun aFailedBatchIsRetriedUnchanged() {
        errorAnswers = mutableListOf(500, 503)
        SdkInitializer.recordErrorReporting(true)
        report()
        await { errorBatches().size >= 2 }
        // Reported between the attempts: it waits for the next batch, and changes nothing in this one.
        report(ErrorReport.Code.LOAD_TIMEOUT)
        await { errorBatches().size >= 4 }
        val attempts = errorBodies.take(3).map { it.decodeToString() }
        assertEquals("the same body, byte for byte, each time: $attempts", 1, attempts.toSet().size)
        val next = errorBatches()[3]
        assertEquals(listOf("ad.load_timeout"), next["errors"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
    }

    @Test fun theEnvelopeAndSeparatorsCountTowardTheByteLimit() {
        Environment.now = { 1_000_000 }
        // Fifty reports of exactly 1310 bytes: 65 500 bytes of reports, within the limit by themselves, not
        // with the envelope and the commas around them.
        val target = ReportQueue.MAX_BATCH_BYTES / ReportQueue.MAX_BATCH
        fun sizeOf(detail: String): Int {
            report(detail = detail)
            return ErrorReporter.bufferedSizes.last()
        }
        var moment = 0
        while (sizeOf("x") < target - 63) DebugReporter.event("m${moment++}") // a moment adds under 64 bytes
        val detail = "x".repeat(target - sizeOf("x") + 1)
        ErrorReporter.resetForTesting()
        ErrorReporter.intervalMillis = 10
        repeat(ReportQueue.MAX_BATCH) { report(detail = detail) }
        assertEquals(List(ReportQueue.MAX_BATCH) { target }, ErrorReporter.bufferedSizes)
        SdkInitializer.recordErrorReporting(true)
        await { errorBatches().sumOf { it["errors"]!!.jsonArray.size } == ReportQueue.MAX_BATCH }
        val sizes = errorBodies.map { it.size }
        assertTrue("each whole body within ${ReportQueue.MAX_BATCH_BYTES} bytes: $sizes", sizes.all { it <= ReportQueue.MAX_BATCH_BYTES })
        assertEquals("what did not fit went in the next batch: $sizes", 2, sizes.size)
    }

    @Test fun everyBatchFitsItsByteLimitWholeAndNoReportIsLost() {
        // The history every report carries, at its longest: thirty moments.
        repeat(30) { DebugReporter.event("ad.shown", bidId = "ad-$it-0123456789abcdef", properties = mapOf("format" to "overlay")) }
        val count = 60 // about 3.7 KB each: all within the 256 KB buffer, about four batches
        repeat(count) { report() }
        assertEquals("all buffered", count, ErrorReporter.bufferedCount)
        SdkInitializer.recordErrorReporting(true)
        await { errorBatches().sumOf { it["errors"]!!.jsonArray.size } == count }
        val sizes = errorBodies.map { it.size }
        assertTrue("several batches: the byte limit decides, $sizes", sizes.size > 2)
        assertTrue("each whole body within ${ReportQueue.MAX_BATCH_BYTES} bytes: $sizes", sizes.all { it <= ReportQueue.MAX_BATCH_BYTES })
        assertTrue("and filled close to it: $sizes", sizes.first() > ReportQueue.MAX_BATCH_BYTES - 4_096)
    }

    @Test fun goneStopsReportingForTheLaunch() {
        errorAnswers = mutableListOf(410)
        SdkInitializer.recordErrorReporting(true)
        report()
        await { errorBatches().size == 1 }
        report()
        settle()
        assertEquals(1, errorBatches().size)
    }

    @Test fun aRefusedBatchIsDroppedNotRetried() {
        errorAnswers = mutableListOf(400)
        SdkInitializer.recordErrorReporting(true)
        report()
        await { errorBatches().size == 1 }
        report(ErrorReport.Code.LOAD_TIMEOUT)
        await { errorBatches().size == 2 }
        assertEquals("ad.load_timeout", errorBatches()[1]["errors"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun any2xxDeliversABatchWhateverItsBody() {
        SdkInitializer.recordErrorReporting(true)
        for ((index, body) in listOf("OK", "<html>proxy</html>", "{").withIndex()) {
            errorBody = body
            report(detail = body)
            await { errorBatches().size == index + 1 }
            settle()
            assertEquals("delivered once, not retried: $body", index + 1, errorBatches().size)
            assertEquals(0, ErrorReporter.bufferedCount)
        }
    }

    @Test fun aRefusalOrGoneEndsTheFailureStreak() {
        errorAnswers = mutableListOf(500, 500, 400)
        SdkInitializer.recordErrorReporting(true)
        report()
        await { errorBatches().size == 3 }
        settle()
        assertEquals("the 400 was an answer: the next failure waits the base delay", 0, ErrorReporter.failures)
    }

    @Test fun beyondTheBufferTheOldestGo() {
        repeat(ReportQueue.MAX_BUFFERED + 5) { report() }
        assertEquals(ReportQueue.MAX_BUFFERED, ErrorReporter.bufferedCount)
        SdkInitializer.recordErrorReporting(true)
        await { errorBatches().sumOf { it["errors"]!!.jsonArray.size } == ReportQueue.MAX_BUFFERED }
        val batches = errorBatches()
        assertTrue("at most ${ReportQueue.MAX_BATCH} a batch", batches.all { it["errors"]!!.jsonArray.size <= ReportQueue.MAX_BATCH })
    }

    @Test fun aRequestFailureBecomesStatusOrClass() {
        val http = ErrorReport.FailedRequest(ApiException.Http(503, emptyList()))
        assertEquals(503, http.httpStatus)
        assertNull(http.cause)
        val transport = ErrorReport.FailedRequest(ApiException.Transport(IOException("down")))
        assertEquals("network", transport.detail)
        assertEquals(ErrorReport.Cause("java.io.IOException", 0), transport.cause)
        val decoding = ErrorReport.FailedRequest(ApiException.Decoding(IllegalArgumentException("bad")))
        assertEquals(ErrorReport.Cause("java.lang.IllegalArgumentException", 0), decoding.cause)
        assertNull(decoding.detail)
        assertEquals(ErrorReport.Cause("java.lang.IllegalStateException", 0), ErrorReport.FailedRequest(IllegalStateException()).cause)
    }

    @Test fun debugEventsLeaveOnlyWhenTheServerAsked() {
        DebugReporter.event("before")
        settle()
        assertTrue(received.none { it.first == "/v2/debug" })
        SdkInitializer.recordDebugReporting(true)
        DebugReporter.event("ads.request", level = DebugReporter.Level.WARNING, properties = mapOf("held" to 1, "playhead" to 2.5))
        await { received.any { it.first == "/v2/debug" } }
        val event = received.first { it.first == "/v2/debug" }.second["events"]!!.jsonArray.single().jsonObject
        assertEquals("ads.request", event["name"]!!.jsonPrimitive.content)
        assertEquals("warning", event["level"]!!.jsonPrimitive.content)
        assertEquals("ads.request ad=f1a2b3c4 held=1 playhead=2.5", DebugReporter.line("ads.request", "f1a2b3c4deadbeef", mapOf("playhead" to 2.5, "held" to 1)))
        SdkInitializer.recordDebugReporting(false)
        DebugReporter.event("after")
        settle()
        assertEquals(1, received.count { it.first == "/v2/debug" })
    }

    @Test fun debugEventsAndTheHistoryKeepEveryStringCapped() {
        SdkInitializer.recordDebugReporting(true)
        val huge = "x".repeat(100_000)
        repeat(3) { DebugReporter.event("ad.shown", bidId = huge, properties = mapOf("reason" to huge, "e" to so.kontext.video.AdFormat.OVERLAY, "n" to Double.NaN)) }
        assertTrue("kept capped", EventHistory.recent().all { (it["bidId"]?.jsonPrimitive?.content?.length ?: 0) <= 64 })
        await { received.any { it.first == "/v2/debug" } }
        val bodies = received.filter { it.first == "/v2/debug" }.map { it.second }
        val events = bodies.flatMap { it["events"]!!.jsonArray.map { event -> event.jsonObject } }
        assertTrue(events.all { it["bidId"]!!.jsonPrimitive.content.toByteArray().size <= 64 })
        assertTrue(events.all { it["properties"]!!.jsonObject["reason"]!!.jsonPrimitive.content.toByteArray().size <= 64 })
        // Strict JSON: the NaN went as text, the enum by its name.
        bodies.forEach { so.kontext.video.internal.payloads.JsonParser.parse(it.toString()) }
    }

    @Test fun theHistoryKeepsTheLastThirty() {
        repeat(40) { DebugReporter.event("e$it") }
        val history = EventHistory.recent()
        assertEquals(EventHistory.HISTORY_LENGTH, history.size)
        assertEquals("e10", history.first()["name"]!!.jsonPrimitive.content)
        assertFalse(history.any { it["name"]!!.jsonPrimitive.content == "e9" })
    }

    @Test fun aReportThatFitsNoBatchDoesNotStopTheNextOneAtIntervalZero() {
        // A report just under the batch budget alone and over it inside the envelope: the flush drops it without
        // suspending. On the immediate main dispatcher that flush is over before `launch` returns. Sized while the
        // reports still wait for /config, so nothing leaves during the calibration.
        fun sizeWithHistory(length: Int): Int {
            ErrorReporter.resetForTesting()
            EventHistory.clear()
            repeat(EventHistory.HISTORY_LENGTH) { DebugReporter.forward(Configuration.LogLevel.WARNING, "m".repeat(length)) }
            shadowOf(Looper.getMainLooper()).idle()
            report(detail = "probe")
            return ErrorReporter.bufferedSizes.single()
        }
        val base = sizeWithHistory(10)
        val perChar = (sizeWithHistory(20) - base) / (10 * EventHistory.HISTORY_LENGTH) // 1: one byte a character
        var length = 10 + (ReportQueue.MAX_BATCH_BYTES - 100 - base) / (perChar * EventHistory.HISTORY_LENGTH)
        var size = sizeWithHistory(length)
        while (size !in (ReportQueue.MAX_BATCH_BYTES - 200)..ReportQueue.MAX_BATCH_BYTES) {
            length += if (size > ReportQueue.MAX_BATCH_BYTES) -1 else 1
            size = sizeWithHistory(length)
        }
        assertEquals("the oversized report waits for /config", 1, ErrorReporter.bufferedCount)
        ErrorReporter.intervalMillis = 0
        SdkInitializer.recordErrorReporting(true) // flushes at once: the report fits no batch and goes, synchronously
        shadowOf(Looper.getMainLooper()).idle()
        EventHistory.clear()
        report(detail = "after")
        await { errorBatches().any { batch -> batch["errors"]!!.jsonArray.any { it.jsonObject["detail"]?.jsonPrimitive?.content == "after" } } }
    }

    @Test fun aLineLoggedOffTheMainThreadIsForwardedUncapped() {
        SdkInitializer.recordDebugReporting(true)
        val long = "x".repeat(200)
        Thread { Logger.warning { "canary-thread HTTPS://Ads.Test/F?ifa=secret kontext-test://frames/x?q=1 brandapp:offer?ifa=secret brandapp:?ifa=secret $long" } }.apply {
            start()
            join()
        }
        await { debugEvents().any { it["properties"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.startsWith("canary-thread") == true } }
        val message = debugEvents().first { it["properties"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.startsWith("canary-thread") == true }["properties"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertEquals("every scheme, any case, with or without //, the text whole", "canary-thread HTTPS://Ads.Test/F kontext-test://frames/x brandapp:offer brandapp: $long", message)
    }

    private fun debugEvents() = received.filter { it.first == "/v2/debug" }.flatMap { it.second["events"]!!.jsonArray.map { event -> event.jsonObject } }

    @Test fun aDebugBatchCarriesTheAppAndTheOS() {
        SdkInitializer.recordDebugReporting(true)
        DebugReporter.event("tick")
        await { received.any { it.first == "/v2/debug" } }
        val body = received.first { it.first == "/v2/debug" }.second
        assertTrue(body["app"]!!.jsonObject.containsKey("bundleId"))
        assertEquals(setOf("name", "version"), body["os"]!!.jsonObject.keys)
    }

    @Test fun logcatLinesAreForwardedAsLogEventsWhileDebugIsOnOnly() {
        Logger.warning { "canary-off https://ads.test/f?ifa=secret" }
        settle()
        assertTrue(received.none { it.first == "/v2/debug" })
        SdkInitializer.recordDebugReporting(true)
        Logger.level = Configuration.LogLevel.NONE // Logcat shows nothing; the server still gets every line
        Logger.warning { "canary-on https://ads.test/f?ifa=secret&x=1 answered 410" }
        Logger.debug { "canary-debug" }
        await { debugEvents().any { it["properties"]?.jsonObject?.get("message")?.jsonPrimitive?.content == "canary-debug" } }
        val line = debugEvents().single { it["properties"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.startsWith("canary-on") == true }
        assertEquals("log", line["name"]!!.jsonPrimitive.content)
        assertEquals("warning", line["level"]!!.jsonPrimitive.content)
        assertEquals("the URL without its query", "canary-on https://ads.test/f answered 410", line["properties"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertFalse("the query never leaves", received.any { it.second.toString().contains("secret") })
    }

    @Test fun anIntervalOfZeroSendsEachEventAsItHappens() {
        DebugReporter.intervalMillis = 0
        SdkInitializer.recordDebugReporting(true)
        DebugReporter.event("alone-a")
        await { debugEvents().any { it["name"]!!.jsonPrimitive.content == "alone-a" } }
        DebugReporter.event("alone-b")
        await { debugEvents().any { it["name"]!!.jsonPrimitive.content == "alone-b" } }
        val batches = received.filter { it.first == "/v2/debug" }.map { it.second["events"]!!.jsonArray.map { e -> e.jsonObject["name"]!!.jsonPrimitive.content } }
        assertTrue("each left on its own: $batches", batches.none { "alone-a" in it && "alone-b" in it })
    }
}
