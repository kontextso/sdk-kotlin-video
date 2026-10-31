package so.kontext.video.internal

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
import so.kontext.video.Configuration
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** The error and debug reports, delivered to a local server: when they leave, in what batches, and what stops them. */
@RunWith(RobolectricTestRunner::class)
class ReportersTest {
    private val server = MockWebServer()
    private val received = ConcurrentLinkedQueue<Pair<String, JsonObject>>()

    @Volatile private var errorAnswers: MutableList<Int> = mutableListOf()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                received += path to Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val status = if (path == "/v2/error") synchronized(this) { errorAnswers.removeFirstOrNull() } ?: 204 else 204
                return MockResponse().setResponseCode(status)
            }
        }
        server.start()
        Environment.context = ApplicationProvider.getApplicationContext()
        Sdk.configureForTesting(Configuration("test-token") { adServerUrl = server.url("/").toString() })
        ErrorReporter.intervalMillis = 10
        ErrorReporter.retryDelayMillis = 50
        ErrorReporter.maxRetryDelayMillis = 100
        DebugReporter.intervalMillis = 10
    }

    @After fun stop() {
        Sdk.resetForTesting()
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

    private fun report(code: ErrorReporter.Code = ErrorReporter.Code.CREATIVE_FAILED) =
        ErrorReporter.report(code, ErrorReporter.Stage.PLAYBACK, adId = "ad-1", vastCode = 405, detail = "x")

    @Test fun reportsWaitForConfigAndLeaveTogetherWithTheirHistory() {
        DebugReporter.event("ad.shown", adId = "ad-1", state = mapOf("format" to "overlay", "secret" to "no"))
        report()
        report(ErrorReporter.Code.LOAD_TIMEOUT)
        settle()
        assertTrue("nothing leaves before /config", errorBatches().isEmpty())
        ErrorReporter.configure(true)
        await { errorBatches().isNotEmpty() }
        val batch = errorBatches().single()
        assertEquals("test-token", batch["publisherToken"]!!.jsonPrimitive.content)
        assertEquals(0, batch["dropped"]!!.jsonPrimitive.int)
        val errors = batch["errors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("ad.creative_failed", "ad.load_timeout"), errors.map { it["code"]!!.jsonPrimitive.content })
        assertEquals("playback", errors[0]["stage"]!!.jsonPrimitive.content)
        assertEquals(405, errors[0]["vastCode"]!!.jsonPrimitive.int)
        val moment = errors[0]["history"]!!.jsonArray.single().jsonObject
        assertEquals("ad.shown", moment["name"]!!.jsonPrimitive.content)
        assertEquals("only the listed state keys", setOf("format"), moment["state"]!!.jsonObject.keys)
    }

    @Test fun configWithoutReportsDropsThemForTheLaunch() {
        report()
        ErrorReporter.configure(false)
        report()
        ErrorReporter.configure(true) // off is final
        report()
        settle()
        assertTrue(errorBatches().isEmpty())
        assertEquals(0, ErrorReporter.bufferedCount)
    }

    @Test fun aFailedBatchIsRetriedUnchanged() {
        errorAnswers = mutableListOf(500, 503)
        ErrorReporter.configure(true)
        report()
        await { errorBatches().size >= 3 }
        val ids = errorBatches().map { it["batchId"]!!.jsonPrimitive.content }
        assertEquals("the same batch each time", 1, ids.toSet().size)
    }

    @Test fun goneStopsReportingForTheLaunch() {
        errorAnswers = mutableListOf(410)
        ErrorReporter.configure(true)
        report()
        await { errorBatches().size == 1 }
        report()
        settle()
        assertEquals(1, errorBatches().size)
    }

    @Test fun aRefusedBatchIsDroppedNotRetried() {
        errorAnswers = mutableListOf(400)
        ErrorReporter.configure(true)
        report()
        await { errorBatches().size == 1 }
        report(ErrorReporter.Code.LOAD_TIMEOUT)
        await { errorBatches().size == 2 }
        assertEquals("ad.load_timeout", errorBatches()[1]["errors"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun beyondTheBufferTheOldestGoAndTheNextBatchSaysHowMany() {
        repeat(ErrorReporter.MAX_BUFFERED + 5) { report() }
        assertEquals(ErrorReporter.MAX_BUFFERED, ErrorReporter.bufferedCount)
        ErrorReporter.configure(true)
        await { errorBatches().sumOf { it["errors"]!!.jsonArray.size } == ErrorReporter.MAX_BUFFERED }
        val batches = errorBatches()
        assertEquals(5, batches.first()["dropped"]!!.jsonPrimitive.int)
        assertTrue("at most ${ErrorReporter.MAX_BATCH} a batch", batches.all { it["errors"]!!.jsonArray.size <= ErrorReporter.MAX_BATCH })
        assertEquals(0, batches.last()["dropped"]!!.jsonPrimitive.int)
    }

    @Test fun aRequestFailureBecomesStatusOrClass() {
        val http = ErrorReporter.RequestFailure(ApiException.Http(503, emptyList()))
        assertEquals(503, http.httpStatus)
        assertNull(http.underlying)
        val transport = ErrorReporter.RequestFailure(ApiException.Transport(IOException("down")))
        assertEquals("network", transport.detail)
        assertEquals("java.io.IOException", transport.underlying)
        val decoding = ErrorReporter.RequestFailure(ApiException.Decoding(IllegalArgumentException("bad")))
        assertEquals("java.lang.IllegalArgumentException", decoding.underlying)
        assertNull(decoding.detail)
        assertEquals("java.lang.IllegalStateException", ErrorReporter.RequestFailure(IllegalStateException()).underlying)
    }

    @Test fun debugEventsLeaveOnlyWhenTheServerAsked() {
        DebugReporter.event("before")
        settle()
        assertTrue(received.none { it.first == "/v2/debug" })
        DebugReporter.isEnabled = true
        DebugReporter.event("ads.request", level = DebugReporter.Level.WARNING, state = mapOf("held" to 1, "playhead" to 2.5))
        await { received.any { it.first == "/v2/debug" } }
        val event = received.first { it.first == "/v2/debug" }.second["events"]!!.jsonArray.single().jsonObject
        assertEquals("ads.request", event["name"]!!.jsonPrimitive.content)
        assertEquals("warning", event["level"]!!.jsonPrimitive.content)
        assertEquals("ads.request ad=f1a2b3c4 held=1 playhead=2.5", DebugReporter.line("ads.request", "f1a2b3c4deadbeef", mapOf("playhead" to 2.5, "held" to 1)))
        DebugReporter.isEnabled = false
        DebugReporter.event("after")
        settle()
        assertEquals(1, received.count { it.first == "/v2/debug" })
    }

    @Test fun theHistoryKeepsTheLastThirty() {
        repeat(40) { DebugReporter.event("e$it") }
        val history = DebugReporter.recentHistory()
        assertEquals(DebugReporter.HISTORY_LENGTH, history.size)
        assertEquals("e10", history.first().name)
        assertFalse(history.any { it.name == "e9" })
    }
}
