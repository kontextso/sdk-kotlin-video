package so.kontext.video.internal.diagnostics

import android.app.Activity
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.ParityHarness
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.LinearScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Swift error and debug reporting suites' rules that the other Kotlin tests did not check yet: when reports
 * leave, what they carry, and what they never carry.
 */
@RunWith(RobolectricTestRunner::class)
class ParitySupportTest : ParityHarness() {
    // region Initialization

    private val reportsOn = """{"enabled": true, "reportErrors": true}"""

    private val debugOn = """{"enabled": true, "reportDebug": true}"""

    @Test fun initializeInAnotherProcessOfTheAppStaysOffWithoutAServerCall() {
        SdkInitializer.resetForTesting()
        so.kontext.video.internal.state.SdkState.current.recordUser(so.kontext.video.KontextVideo.User("test-user"))
        answer("/v2/config", """{"enabled": true}""")
        val app = Environment.context!!
        Environment.processName = { "${app.packageName}:playback" }
        var heard: KontextVideo.State? = null
        KontextVideo.initialize(app, Configuration("test-token") { adServerUrl = baseUrl }) { heard = it }
        assertEquals(KontextVideo.State.Disabled(SdkInitializer.NOT_MAIN_PROCESS), KontextVideo.state)
        await { heard != null }
        assertEquals("the listener is told", KontextVideo.State.Disabled(SdkInitializer.NOT_MAIN_PROCESS), heard)
        settle()
        assertTrue("no /config", requests("/v2/config").isEmpty())
        // An ads request there is refused as for a switched-off SDK, not as a call before initialize.
        val call = startFormat(owner(), AdFormat.OVERLAY)
        await { call.result != null }
        assertTrue("${call.result}", call.result!!.exceptionOrNull() is VideoAdException.Disabled)
    }

    @Test fun initializeInTheMainProcessCallsTheServer() {
        val app = Environment.context!!
        Environment.processName = { app.applicationInfo.processName }
        assertEquals(KontextVideo.State.Enabled, initialize())
        assertEquals(1, requests("/v2/config").size)
        Environment.processName = { null } // unreadable: taken as the main process
        answers.remove("/v2/config")
        assertEquals(KontextVideo.State.Enabled, initialize())
    }

    // endregion

    // region ErrorReporter

    @Test fun reportsAreOffWhenConfigLeavesThemOutOrDisablesTheSdk() {
        initialize()
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, detail = "x")
        assertEquals("reportErrors left out is off", 0, ErrorReporter.bufferedCount)
        answers.remove("/v2/config")
        initialize("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, detail = "x")
        assertEquals("a disabled SDK reports nothing", 0, ErrorReporter.bufferedCount)
        settle()
        assertTrue(requests("/v2/error").isEmpty())
    }

    @Test fun aFailedConfigWaitsAndLeavesWithTheIdOfItsRequestOnceALaterConfigTurnsReportsOn() {
        answer("/v2/config") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertEquals(KontextVideo.State.Failed, initialize())
        val sent = requests("/v2/config").map { it.json!!.str("requestId") }
        assertEquals("one attempt", 1, sent.size)
        assertEquals("kept in memory", listOf("config.failed"), ErrorReporter.bufferedCodes)
        answer("/v2/config", reportsOn)
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        format(owner(), AdFormat.PAUSE).getOrThrow() // retries /config, which turns the reports on
        await { errorReports("config.failed").isNotEmpty() }
        val report = errorReports("config.failed").single()
        assertEquals("network", report.str("detail"))
        assertTrue("${report["cause"]}", report["cause"]!!.jsonObject.str("domain")!!.startsWith("java."))
        assertEquals("the id of the request that failed", sent.single(), report.str("requestId"))
    }

    @Test fun aFailedRequestSharedByThreeCallsIsOneReport() {
        initialize(reportsOn)
        val gate = CountDownLatch(1)
        answer("/v2/video/ads") {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(500).setBody("""{"errors": [{"status": "500", "code": "internal"}]}""")
        }
        val ads = owner()
        try {
            val calls = AdFormat.entries.map { startFormat(ads, it) }
            await { requests("/v2/video/ads").size == 1 }
            gate.countDown()
            await { calls.all { it.result != null } }
        } finally {
            gate.countDown()
        }
        await { errorReports("ads.request_failed").isNotEmpty() }
        settle()
        val report = errorReports("ads.request_failed").single()
        assertEquals("one request, one report, however many callers waited", 1, requests("/v2/video/ads").size)
        assertEquals(500, report["httpStatus"]!!.jsonPrimitive.int)
        assertNotNull(ads.lastRequestId)
        assertEquals("the id the app can quote", ads.lastRequestId, report.str("requestId"))
        assertEquals(ads.sessionId, report.str("sessionId"))
        val moment = report["history"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == "ads.request" }
        val createdAt = moment.str("occurredAt")!!
        assertTrue("ISO 8601 with milliseconds in UTC: $createdAt", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(createdAt))
        assertNull("a date, not seconds before the error", moment["at"])
    }

    @Test fun anAnswerThatBreaksTheContractIsADecodeFailure() {
        initialize(reportsOn)
        answer("/v2/video/ads", """{"nonsense": true}""")
        assertTrue(format(owner(), AdFormat.PAUSE).isFailure)
        await { errorReports("ads.decode_failed").isNotEmpty() }
        assertTrue(errorReports("ads.request_failed").isEmpty())
    }

    @Test fun theBodyCarriesIdsAndNeverPersonalData() {
        initialize(reportsOn)
        KontextVideo.user = User("user-secret-1") {
            email = "secret@example.com"
        }
        KontextVideo.regulatory = Regulatory { gdprConsent = "CONSENT-SECRET" }
        Environment.advertisingId = { "aaid-secret-0000" }
        answer("/v2/video/ads", """{"errors": [{"status": "500", "code": "internal"}]}""", 500)
        format(owner(), AdFormat.PAUSE)
        await { requests("/v2/error").isNotEmpty() }
        val request = requests("/v2/error").first()
        for (secret in listOf("user-secret-1", "secret@example.com", "CONSENT-SECRET", "aaid-secret-0000")) {
            assertFalse("$secret in ${request.text}", request.text.contains(secret))
        }
        val body = request.json!!
        assertEquals("test-token", body.str("publisherToken"))
        assertEquals(DeviceInfo.installId(Environment.context!!), body.str("installId"))
        assertEquals("sdk-kotlin-video", body["sdk"]!!.jsonObject.str("name"))
        assertNotNull(body["app"]!!.jsonObject["bundleId"])
        assertNotNull(body["os"]!!.jsonObject["name"])
    }

    @Test fun aBatchWhoseRequestFailsInTransitIsRetriedUnchanged() {
        var calls = 0
        answer("/v2/error") {
            calls += 1
            if (calls == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setResponseCode(204)
        }
        SdkInitializer.recordErrorReporting(true)
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, bidId = "ad-1", detail = "start")
        await { requests("/v2/error").size >= 2 }
        settle()
        val bodies = requests("/v2/error").map { it.text }
        assertEquals("delivered once after the failure: $bodies", 2, bodies.size)
        assertEquals("the same batch, byte for byte, so the server counts a retry again", bodies[0], bodies[1])
    }

    @Test fun theCreativesFailuresAreToldApartBehindTheSameVastCode() {
        SdkInitializer.recordErrorReporting(true)
        val ads = owner()
        val terminated = overlay(ads)
        terminated.show(container())
        val bridge = terminated.loadedBridge!!
        val gone: RenderProcessGoneDetail = mockk(relaxed = true)
        bridge.webView.webViewClient.onRenderProcessGone(bridge.webView, gone)
        val pageError = pause(ads)
        page(pageError, "ad.error", "\"code\": 405")
        await { errorReports().size >= 2 }
        val process = errorReports("frame.process_terminated").single()
        assertEquals(405, process["vastCode"]!!.jsonPrimitive.int)
        assertEquals(terminated.id, process.str("bidId"))
        assertEquals(ads.sessionId, process.str("sessionId"))
        val creative = errorReports("ad.creative_failed").single()
        assertEquals(405, creative["vastCode"]!!.jsonPrimitive.int)
    }

    @Test fun aPageThatBreaksTheMessageContractIsReportedOncePerAd() {
        SdkInitializer.recordErrorReporting(true)
        val ad = pause()
        val bridge = ad.loadedBridge!!
        val origin = Origin.of(ad.payload.frameUrl)
        bridge.receive("""{"bidId":"someone-else","type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"bidId":"${ad.id}","type":"ad.fromTheFuture"}""", origin, true) // a newer page: not a violation
        bridge.receive("""{"bidId":"x"}""", Origin.of("https://creative.test"), false)
        await { errorReports("frame.protocol_violation").isNotEmpty() }
        settle()
        val violations = errorReports("frame.protocol_violation")
        assertEquals("$violations", 1, violations.size)
        assertEquals("envelope", violations.single().str("detail"))
    }

    @Test fun aLinearScreenThatNeverStartsIsReported() {
        SdkInitializer.recordErrorReporting(true)
        LinearScreen.showTimeoutMillis = 300
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ad = linear()
        ad.show(host) // the screen is asked for and never created
        await { errorReports("ad.presentation_failed").isNotEmpty() }
        val report = errorReports("ad.presentation_failed").single()
    }

    @Test fun aNoFillReasonTheSdkDoesNotKnowNeverLeavesTheDevice() {
        initialize(reportsOn)
        val free = "contact secret@example.com at https://evil.test/x"
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", noFill = free)).toString())
        format(owner(), AdFormat.PAUSE)
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, detail = "start") // any fault after it carries the history
        await { errorReports("ad.creative_failed").isNotEmpty() }
        val raw = requests("/v2/error").last().text
        assertFalse("the server's free text stays on the device: $raw", raw.contains("secret@example.com") || raw.contains("evil.test"))
        val reasons = errorReports("ad.creative_failed").single()["history"]!!.jsonArray.mapNotNull { it.jsonObject["properties"]?.jsonObject?.str("reason") }
        assertTrue("an unknown reason is \"other\": $reasons", reasons.contains("other"))
    }

    @Test fun everyStringFieldIsCappedByBytesEvenOneCharacterOfThousandsOfMarks() {
        val heavy = "e" + "́".repeat(5_000) // one character on screen, about 10 KB
        assertTrue(heavy.toByteArray().size > 10_000)
        assertTrue(ReportFormat.capped(heavy).toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
        assertEquals("short", ReportFormat.capped("short"))
        val long = "a".repeat(10_000)
        DebugReporter.event(heavy, bidId = heavy, properties = mapOf("format" to heavy))
        SdkInitializer.recordErrorReporting(true)
        repeat(20) { index ->
            ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, requestId = long, sessionId = heavy, bidId = "$index$long", detail = heavy, cause = ErrorReport.Cause(long, 0))
        }
        await { errorReports().size == 20 }
        for (report in errorReports()) {
            for (field in listOf("requestId", "sessionId", "bidId", "detail")) {
                assertTrue("$field capped", report.str(field)!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            }
            assertTrue(report["cause"]!!.jsonObject.str("domain")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            val moment = report["history"]!!.jsonArray.single().jsonObject
            assertTrue(moment.str("name")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            assertTrue(moment.str("bidId")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            assertTrue(moment["properties"]!!.jsonObject.str("format")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
        }
        val sizes = requests("/v2/error").map { it.bytes.size }
        assertTrue("within the batch budget: $sizes", sizes.all { it <= ReportQueue.MAX_BATCH_BYTES })
    }

    @Test fun anExpiredHeldAdIsNotCountedAsKept() {
        var now = 1_000_000L
        Environment.now = { now }
        initialize(reportsOn)
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("overlay", Fixtures.overlay(expires = 30_000))).toString())
        val ads = owner()
        format(ads, AdFormat.OVERLAY).getOrThrow()
        val gate = CountDownLatch(1)
        answer("/v2/video/ads") {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(500)
        }
        try {
            val call = startFormat(ads, AdFormat.OVERLAY)
            await { requests("/v2/video/ads").size == 2 }
            now += 31_000 // the held ad expires while the request is out
            gate.countDown()
            await { call.result != null }
            assertTrue("no valid ad to fall back to", call.result!!.isFailure)
        } finally {
            gate.countDown()
        }
        await { errorReports("ads.request_failed").isNotEmpty() }
    }

    @Test fun aPageThatCannotLoadCarriesTheSystemsError() {
        SdkInitializer.recordErrorReporting(true)
        val payload = AdPayload.parse(Fixtures.json(Fixtures.pause()))
        val ad = VideoAd(payload, Environment.now(), owner())
        Environment.scope.launch { runCatching { ad.load() } }
        val bridge = ad.loadedBridge!!
        val request: WebResourceRequest = mockk {
            every { url } returns Uri.parse(payload.frameUrl)
            every { isForMainFrame } returns true
        }
        val error: WebResourceError = mockk { every { errorCode } returns -2 }
        bridge.webView.webViewClient.onReceivedError(bridge.webView, request, error)
        await { errorReports("frame.load_failed").isNotEmpty() }
        val cause = errorReports("frame.load_failed").single()["cause"]!!.jsonObject
        assertEquals("webview", cause.str("domain"))
        assertEquals("-2", cause["code"].toString())
    }

    @Test fun integrationMistakesAreReportedOnceTheServerAllowsIt() {
        SdkInitializer.resetForTesting()
        so.kontext.video.internal.state.SdkState.current.recordUser(so.kontext.video.KontextVideo.User("test-user"))
        fastReporters()
        val early = VideoAds(Content("v", "en")) // before initialize: waits in memory
        answer("/v2/config", reportsOn)
        var state: KontextVideo.State? = null
        KontextVideo.initialize(Environment.context!!, Configuration("") { adServerUrl = baseUrl }) { state = it }
        await { state != null }
        val bad = VideoAds(Content("", "en"))
        try {
            await { errorReports().size >= 3 }
            settle()
            assertEquals(3, errorReports().size)
            assertEquals("VideoAds(content)", errorReports("integration.not_initialized").single().str("detail"))
            assertEquals(1, errorReports("integration.empty_token").size)
            assertEquals("id is empty", errorReports("integration.bad_content").single().str("detail"))
        } finally {
            early.destroy()
            bad.destroy()
        }
    }

    // endregion

    // region DebugReporter

    @Test fun enabledByConfigEventsAreBatchedWithTheBodyShape() {
        initialize(debugOn)
        DebugReporter.event("ads.request", properties = mapOf("format" to "pause", "playhead" to 1200, "filled" to true))
        DebugReporter.event("ad.loaded", bidId = "ad-1", level = DebugReporter.Level.WARNING, properties = mapOf("durationMs" to 842))
        await { debugBatches("ad.loaded").isNotEmpty() }
        val body = requests("/v2/debug").last().json!!
        assertEquals("test-token", body.str("publisherToken"))
        assertEquals(DeviceInfo.installId(Environment.context!!), body.str("installId"))
        assertEquals("sdk-kotlin-video", body["sdk"]!!.jsonObject.str("name"))
        assertTrue("config.response", debugNames().contains("config.response"))
        val events = debugBatches().flatten()
        val request = events.first { it.str("name") == "ads.request" }
        val createdAt = request.str("occurredAt")!!
        assertTrue("ISO 8601 with milliseconds, UTC: $createdAt", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(createdAt))
        assertNull(request["bidId"])
        val state = request["properties"]!!.jsonObject
        assertEquals("pause", state.str("format"))
        assertEquals(1200.0, state["playhead"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("true", state["filled"]!!.jsonPrimitive.content)
        assertFalse(state["filled"]!!.jsonPrimitive.isString)
        val loaded = events.first { it.str("name") == "ad.loaded" }
        assertEquals("ad-1", loaded.str("bidId"))
        assertEquals("warning", loaded.str("level"))
    }

    @Test fun nothingIsSentWhenConfigDoesNotAsk() {
        initialize()
        DebugReporter.event("ads.request", properties = mapOf("format" to "pause"))
        settle()
        assertTrue(requests("/v2/debug").isEmpty())
    }

    @Test fun eventsAreCoalescedIntoOneRequestPerInterval() {
        SdkInitializer.recordDebugReporting(true)
        repeat(20) { DebugReporter.event("tick", properties = mapOf("i" to it)) }
        await { debugBatches("tick").isNotEmpty() }
        settle()
        val batches = debugBatches("tick")
        assertEquals("twenty events, one request", 1, batches.size)
        assertEquals(20, batches.single().count { it.str("name") == "tick" })
    }

    @Test fun disablingDropsTheBuffer() {
        SdkInitializer.recordDebugReporting(true)
        DebugReporter.event("tick")
        SdkInitializer.recordDebugReporting(false)
        SdkInitializer.recordDebugReporting(true)
        DebugReporter.event("after")
        await { debugBatches("after").isNotEmpty() }
        settle()
        assertTrue("the tick was buffered, then dropped", debugBatches("tick").isEmpty())
    }

    @Test fun aFailedBatchIsLostQuietlyAndTheNextOneGoesOut() {
        SdkInitializer.recordDebugReporting(true)
        answer("/v2/debug") { MockResponse().setResponseCode(500) }
        DebugReporter.event("lost-once")
        await { debugBatches("lost-once").isNotEmpty() }
        answer("/v2/debug") { MockResponse().setResponseCode(204) }
        DebugReporter.event("after-the-loss")
        await { debugBatches("after-the-loss").isNotEmpty() }
        settle()
        assertEquals("a failed batch is never sent again", 1, debugBatches("lost-once").size)
        assertEquals("the next event goes out once", 1, debugBatches("after-the-loss").size)
        assertTrue("reporting stays on", DebugReporter.isEnabled)
    }

    @Test fun debugReportsCarryNoneOfTheViewersData() {
        initialize(debugOn)
        KontextVideo.user = User("canary-user-id") {
            email = "canary@viewer.test"
            emailHash = "canary-hash"
            keywords = listOf("canary-keyword")
        }
        KontextVideo.regulatory = Regulatory {
            gdprConsent = "canary-tc-string"
            gpp = "canary-gpp"
            usPrivacy = "canary-usp"
        }
        Environment.advertisingId = { "canary-aaid" }
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        format(owner(Content("ep", "en") { title = "canary-title" }), AdFormat.PAUSE).getOrThrow()
        await { debugNames().contains("ads.response") }
        for (body in requests("/v2/debug").map { it.text }) {
            for (canary in listOf("canary-user-id", "canary@viewer.test", "canary-hash", "canary-keyword", "canary-tc-string", "canary-gpp", "canary-usp", "canary-title", "canary-aaid")) {
                assertFalse("$canary reached a debug report", body.contains(canary))
            }
            assertFalse("never the forbidden fields: $body", body.contains("ifa") || body.contains("email") || body.contains("gdprConsent"))
        }
    }

    @Test fun oneRequestAtATimeAndTheRestWaitForIt() {
        SdkInitializer.recordDebugReporting(true)
        val gate = CountDownLatch(1)
        answer("/v2/debug") {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(204)
        }
        try {
            DebugReporter.event("first")
            await { debugBatches("first").isNotEmpty() }
            repeat(5) { DebugReporter.event("later", properties = mapOf("i" to it)) }
            settle()
            assertTrue("no second request while the first is answered", debugBatches("later").isEmpty())
            gate.countDown()
            await { debugBatches("later").isNotEmpty() }
            settle()
            val later = debugBatches("later")
            assertEquals("the five left together, in one batch", 1, later.size)
            assertEquals(5, later.single().size)
        } finally {
            gate.countDown()
        }
    }

    @Test fun theBufferKeepsTheNewestEventsOnly() {
        SdkInitializer.recordDebugReporting(true)
        val gate = CountDownLatch(1)
        answer("/v2/debug") {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(204)
        }
        try {
            DebugReporter.event("fill") // holds a batch open; the rest pile up behind it
            await { debugBatches("fill").isNotEmpty() }
            repeat(DebugReporter.MAX_BUFFERED + 10) { DebugReporter.event("tick", properties = mapOf("i" to it)) }
            gate.countDown()
            await { debugBatches("tick").isNotEmpty() }
        } finally {
            gate.countDown()
        }
        val batch = debugBatches("tick").single()
        assertEquals(DebugReporter.MAX_BUFFERED, batch.size)
        assertEquals("the oldest ten were dropped", 10, batch.first()["properties"]!!.jsonObject["i"]!!.jsonPrimitive.int)
    }

    @Test fun aNumberBeyondLongFormatsAsItselfWithoutATrap() {
        assertEquals("n a=1.0E30", DebugReporter.line("n", null, mapOf("a" to 1e30)))
        assertEquals("n a=-1.0E30", DebugReporter.line("n", null, mapOf("a" to -1e30)))
        assertEquals("n a=Infinity", DebugReporter.line("n", null, mapOf("a" to Double.POSITIVE_INFINITY)))
        assertEquals("n a=NaN", DebugReporter.line("n", null, mapOf("a" to Double.NaN)))
        assertEquals("n a=42 b=1.5 c=7", DebugReporter.line("n", null, mapOf("a" to 42.0, "b" to 1.5, "c" to 7)))
        assertEquals("config.response", DebugReporter.line("config.response", null, null))
        DebugReporter.event("n", properties = mapOf("a" to Double.POSITIVE_INFINITY)) // the console line is formatted whether or not reporting is on
    }

    @Test fun theLoadFlowReportsItsEvents() {
        initialize(debugOn)
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ad = format(owner(), AdFormat.PAUSE).getOrThrow()
        var loaded: Result<Unit>? = null
        Environment.scope.launch { loaded = runCatching { ad.load() } }
        ad.loadedBridge!!.receive("""{"bidId":"${ad.id}","type":"ad.loaded"}""", Origin.of(ad.payload.frameUrl), true)
        await { loaded != null }
        loaded!!.getOrThrow()
        await { debugNames().contains("ad.loaded") }
        val names = debugNames()
        assertTrue("$names", names.containsAll(listOf("ads.request", "ads.response", "ad.loaded")))
        for (body in requests("/v2/debug").map { it.text }) {
            assertFalse("never the forbidden fields", body.contains("ifa") || body.contains("email") || body.contains("gdprConsent"))
        }
    }

    // endregion
}
