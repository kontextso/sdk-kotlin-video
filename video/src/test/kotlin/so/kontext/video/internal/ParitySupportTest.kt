package so.kontext.video.internal

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.diagnostics.ReportFormat
import so.kontext.video.internal.diagnostics.ReportQueue
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.measurement.Tracker
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.BeaconClient
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import java.net.CacheRequest
import java.net.CacheResponse
import java.net.ResponseCache
import java.net.URI
import java.net.URLConnection
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the parity suites share: the SDK of [SdkTest] pointed at a local server that records every request. */
abstract class ParityHarness : SdkTest() {
    protected val server = MockWebServer()

    /** Every request the server received: its path with the query, its method, content type and body. */
    protected class Received(val path: String, val bytes: ByteArray, val method: String, val contentType: String?) {
        val text get() = bytes.decodeToString()
        val json: JsonObject? get() = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    protected val received = ConcurrentLinkedQueue<Received>()

    /** What a path answers; anything else is `204`. A test replaces an answer whenever it likes. */
    protected val answers = java.util.concurrent.ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    protected val baseUrl get() = server.url("/").toString()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                received += Received(path, request.body.readByteArray(), request.method.orEmpty(), request.getHeader("Content-Type"))
                val route = path.substringBefore('?')
                val answer = answers[route] ?: answers.entries.firstOrNull { route.startsWith(it.key) }?.value
                return answer?.invoke(request) ?: MockResponse().setResponseCode(204)
            }
        }
        server.start()
        SdkInitializer.configureForTesting(Configuration("test-token") { adServerUrl = baseUrl })
        fastReporters()
    }

    @After fun stop() {
        ResponseCache.setDefault(null)
        server.shutdown()
    }

    protected fun fastReporters() {
        ErrorReporter.intervalMillis = 10
        ErrorReporter.retryDelayMillis = 50
        ErrorReporter.maxRetryDelayMillis = 100
        DebugReporter.intervalMillis = 10
    }

    protected fun answer(path: String, response: (RecordedRequest) -> MockResponse) {
        answers[path] = response
    }

    protected fun answer(path: String, body: String, status: Int = 200) = answer(path) { MockResponse().setResponseCode(status).setBody(body) }

    protected fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            // Robolectric's main-looper clock moves only when told: the flush delays run on it.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    /** Lets a few flush intervals pass, to show nothing (more) leaves. */
    protected fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(10)
        }
    }

    protected fun requests(path: String) = received.filter { it.path.substringBefore('?') == path }

    protected fun errorReports() = requests("/v2/error").flatMap { it.json!!["errors"]!!.jsonArray.map { error -> error.jsonObject } }

    protected fun errorReports(code: String) = errorReports().filter { it.str("code") == code }

    protected fun debugBatches() = requests("/v2/debug").map { it.json!!["events"]!!.jsonArray.map { event -> event.jsonObject } }

    protected fun debugBatches(named: String) = debugBatches().filter { events -> events.any { it.str("name") == named } }

    protected fun debugNames() = debugBatches().flatten().map { it.str("name") }

    protected fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    /** `KontextVideo.initialize` from scratch against the local server, `/config` answering [config]. */
    protected fun initialize(config: String = """{"enabled": true}""", token: String = "test-token"): KontextVideo.State {
        SdkInitializer.resetForTesting()
        fastReporters()
        if (answers["/v2/config"] == null) answer("/v2/config", config)
        var state: KontextVideo.State? = null
        KontextVideo.initialize(Environment.context!!, Configuration(token) { adServerUrl = baseUrl }) { state = it }
        await { state != null }
        return state!!
    }

    protected class Call {
        @Volatile var result: Result<VideoAd>? = null
    }

    protected fun startFormat(ads: VideoAds, format: AdFormat): Call {
        val call = Call()
        Environment.scope.launch {
            call.result = try {
                Result.success(ads.format(format))
            } catch (error: VideoAdException) {
                Result.failure(error)
            }
        }
        return call
    }

    protected fun format(ads: VideoAds, format: AdFormat): Result<VideoAd> {
        val call = startFormat(ads, format)
        await { call.result != null }
        return call.result!!
    }

    protected fun query(url: String): Map<String, String> = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
}

/** The Swift tracker suite's rules: selection, fire ids, macros and their encoding, delivery. */
@RunWith(RobolectricTestRunner::class)
class ParityTrackerTest : ParityHarness() {
    // region Tracker

    private fun entry(event: String, url: String, offset: Long? = null, part: String? = null) = AdPayload.TrackingEvent(event, url, offset, part)

    @Test fun clicksAndPlaybackTogglesFireEveryTimeEachWithItsOwnFireId() {
        // The contract's list, written out: derived from `Tracker.REPEATABLE`, a name dropped there would pass.
        val repeatable = listOf("click", "mute", "pause", "resume", "unmute")
        val tracker = Tracker("ad-1", (repeatable + "start").map { entry(it, "https://t.test/b?e=$it&fid=[KONTEXT_FIREID]") })
        val ids = repeatable.associateWith { listOf(tracker.fire(it), tracker.fire(it)) }
        tracker.fire("start")
        tracker.fire("start")
        for (event in repeatable) {
            val fids = beacons.map(::query).filter { it["e"] == event }.map { it["fid"] }
            assertEquals("$event fires each time, each with its own fire id", ids[event], fids)
            assertEquals(2, fids.toSet().size)
        }
        assertEquals("start is once", 1, beacons.count { query(it)["e"] == "start" })
        val second = ids.getValue("click").last()
        assertEquals(
            "the click-through carries its click's fire id; a bracket that is no macro survives",
            "https://ads.test/redirect?fid=$second&q=%5Bkept%5D",
            tracker.clickThrough("https://ads.test/redirect?fid=%5BKONTEXT_FIREID%5D&q=%5Bkept%5D", second),
        )
    }

    @Test fun everyFireCarriesItsOwnFireIdSharedByItsUrls() {
        fun of(event: String, copy: Int) = entry(event, "https://t.test/b?e=$event&copy=$copy&fid=[KONTEXT_FIREID]")
        val tracker = Tracker("ad-1", listOf(of("impression", 1), of("impression", 2), of("click", 1), of("click", 2), of("click", 3)))
        val impression = tracker.fire("impression")
        val click = tracker.fire("click")
        val fired = beacons.map(::query)
        assertEquals("one occurrence, one id across its URLs", listOf(impression, impression), fired.filter { it["e"] == "impression" }.map { it["fid"] })
        assertEquals(listOf(click, click, click), fired.filter { it["e"] == "click" }.map { it["fid"] })
        assertTrue("a different UUID per occurrence", impression != click && UUID.fromString(impression).toString() == impression)
    }

    @Test fun aBeaconIsAGetThatNoHttpCacheAnswers() {
        // An app may install a process-wide HTTP cache; a beacon must reach the server every time all the same.
        val consulted = mutableListOf<URI>()
        ResponseCache.setDefault(object : ResponseCache() {
            override fun get(uri: URI, method: String, headers: MutableMap<String, MutableList<String>>): CacheResponse? {
                consulted += uri
                return null
            }

            override fun put(uri: URI, connection: URLConnection): CacheRequest? = null
        })
        Environment.sendBeacon = BeaconClient::send
        val tracker = Tracker("ad-1", listOf(entry("loaded", server.url("/events/ad-1/loaded").toString())))
        tracker.fire("loaded")
        await { requests("/events/ad-1/loaded").isNotEmpty() }
        assertEquals("GET", requests("/events/ad-1/loaded").single().method)
        assertTrue("the cache is never asked: $consulted", consulted.isEmpty())
    }

    @Test fun anEntryIsSelectedByItsPart() {
        val tracker = Tracker(
            "ad-1",
            listOf(entry("creativeView", "https://t.test/b?e=cv"), entry("creativeView", "https://t.test/b?e=cv&p=endCard", part = "endCard"), entry("click", "https://t.test/b?e=c&p=endCard", part = "endCard")),
        )
        tracker.fire("creativeView", part = "endCard")
        tracker.fire("click") // the main creative has no click entry here
        tracker.fire("creativeView")
        tracker.fire("click", part = "endCard")
        assertEquals(listOf("cv/endCard", "cv/main", "c/endCard"), beacons.map(::query).map { "${it["e"]}/${it["p"] ?: "main"}" })
    }

    @Test fun progressIsSelectedByItsOffset() {
        val tracker = Tracker("ad-1", listOf(entry("progress", "https://t.test/p?o=5000", offset = 5_000), entry("progress", "https://t.test/p?o=10000", offset = 10_000)))
        tracker.fire("progress", offsetMillis = 4_000)
        tracker.fire("progress", offsetMillis = 5_000)
        tracker.fire("progress", offsetMillis = 5_000)
        assertEquals(listOf("https://t.test/p?o=5000"), beacons)
    }

    @Test fun progressFiresEveryPassedCheckpointOnceEachWithItsOwnFireIdAndOneIdAcrossItsUrls() {
        fun of(offset: Long, copy: Int) = entry("progress", "https://t.test/p?offset=$offset&copy=$copy&fid=[KONTEXT_FIREID]", offset = offset)
        val tracker = Tracker("ad-1", listOf(of(10_000, 1), of(5_000, 1), of(10_000, 2), of(15_000, 1)))
        tracker.fireProgress(4_999)
        assertTrue(beacons.isEmpty())
        tracker.fireProgress(12_000) // the page skipped 5000 and rounded 10 000 up
        tracker.fireProgress(12_000)
        val fired = beacons.map(::query)
        assertEquals("5000 once, 10 000 for both its URLs, 15 000 not yet", listOf("5000", "10000", "10000"), fired.map { it["offset"] })
        val tenK = fired.filter { it["offset"] == "10000" }.map { it["fid"] }
        assertEquals("one checkpoint, one fire id across its URLs", 1, tenK.toSet().size)
        assertEquals("separate checkpoints, separate fire ids", 2, fired.map { it["fid"] }.toSet().size)
    }

    @Test fun anEventWithoutAnEntryFiresNothing() {
        val tracker = Tracker("ad-1", AdPayload.parse(Fixtures.json(Fixtures.pause())).tracking + entry("impression", "https://t.test/i"))
        tracker.fire("rewind")
        tracker.fire("start")
        assertTrue("an image creative has no start entry; rewind is not in the list", beacons.isEmpty())
    }

    @Test fun everyMacroIsSubstitutedAndEncodedAsOneQueryValue() {
        val tracker = Tracker(
            "ad-1",
            listOf(entry("error", "https://t.test/e?code=[ERRORCODE]&cph=[MEDIAPLAYHEAD]&aph=[ADPLAYHEAD]&cb=[CACHEBUSTING]&ts=[TIMESTAMP]")),
        )
        tracker.contentPlayhead = { 3_661_250 }
        tracker.adPlayheadMillis = 7_500
        val before = System.currentTimeMillis()
        tracker.fire("error", errorCode = 405)
        val after = System.currentTimeMillis()
        val raw = beacons.single()
        val fired = query(raw)
        assertEquals("405", fired["code"])
        assertEquals("[MEDIAPLAYHEAD] is HH:MM:SS.mmm", "01:01:01.250", fired["cph"])
        assertEquals("[ADPLAYHEAD] is HH:MM:SS.mmm", "00:00:07.500", fired["aph"])
        assertTrue("[CACHEBUSTING] is 8 digits: $raw", Regex("[?&]cb=\\d{8}(&|$)").containsMatchIn(raw))
        val ts = fired.getValue("ts")
        assertTrue("[TIMESTAMP] is ISO 8601 with milliseconds, in the device's zone: $ts", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})$").matches(ts))
        val parsed = OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        assertTrue("the moment of firing", parsed in before..after)
        assertTrue("colons in playheads and the timestamp are escaped: $raw", raw.contains("cph=01%3A01%3A01.250") && !raw.substringAfter("ts=").contains(":"))
    }

    @Test fun bothContentPlayheadNamesShareOneReadAndAnUnknownMacroIsEncodedOnce() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?old=[CONTENTPLAYHEAD]&new=[MEDIAPLAYHEAD]&x=[KONTEXT_UNKNOWN]")))
        tracker.contentPlayhead = {
            reads += 1
            reads * 1_000L // advances on every read
        }
        tracker.fire("impression")
        val fired = query(beacons.single())
        assertEquals("both names, one moment", "00:00:01.000", fired["old"])
        assertEquals("00:00:01.000", fired["new"])
        assertEquals("read once per beacon", 1, reads)
        assertEquals("a macro the SDK does not know stays in place", "[KONTEXT_UNKNOWN]", fired["x"])
        assertTrue("its brackets are encoded, nothing re-encoded: ${beacons.single()}", beacons.single().contains("x=%5BKONTEXT_UNKNOWN%5D") && !beacons.single().contains("%25"))
    }

    @Test fun aPartlyEncodedPlayheadMacroIsStillFilled() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?new=%5BMEDIAPLAYHEAD]&old=[CONTENTPLAYHEAD%5D")))
        tracker.contentPlayhead = {
            reads += 1
            61_500
        }
        tracker.fire("impression")
        val fired = query(beacons.single())
        assertEquals("an encoded opening bracket only", "00:01:01.500", fired["new"])
        assertEquals("an encoded closing bracket only", "00:01:01.500", fired["old"])
        assertEquals("read once for both", 1, reads)
    }

    @Test fun theContentPlayheadIsNotReadWhenNoUrlAsksForIt() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?cb=[CACHEBUSTING]")))
        tracker.contentPlayhead = {
            reads += 1
            1
        }
        tracker.fire("impression")
        assertEquals(1, beacons.size)
        assertEquals(0, reads)
        assertFalse(tracker.readsContentPlayhead)
    }

    @Test fun aWinnerIsEncodedAsOneQueryValue() {
        val tracker = Tracker("ad-1", listOf(entry("notUsed", "https://t.test/n?winner=[KONTEXT_WINNER]&wp=[KONTEXT_WINNINGPRICE]")))
        tracker.fire("notUsed", notUsed = Tracker.NotUsed.Outbid(2.5, "Ad/Net;work?x=1&y=2 +"))
        val raw = beacons.single()
        assertEquals("decoded, the name is intact", "Ad/Net;work?x=1&y=2 +", query(raw)["winner"])
        assertEquals("2.5", query(raw)["wp"])
        assertTrue("nothing a parser could take for a delimiter: $raw", raw.contains("winner=Ad%2FNet%3Bwork%3Fx%3D1%26y%3D2%20%2B&"))
    }

    @Test fun anInvalidUrlIsSkippedWithoutStoppingTheOthers() {
        Environment.sendBeacon = BeaconClient::send
        val valid = { copy: Int -> entry("impression", server.url("/events/ad-1/impression?copy=$copy").toString()) }
        val invalid = entry("impression", "")
        Tracker("ad-1", listOf(invalid, valid(1), invalid, entry("impression", "not a url"), valid(2), invalid)).fire("impression")
        await { requests("/events/ad-1/impression").size >= 2 }
        settle()
        assertEquals(listOf("1", "2"), requests("/events/ad-1/impression").map { query(it.path)["copy"] }.sortedBy { it })
    }

    @Test fun aFailedBeaconIsNotRetried() {
        answer("/events/") { MockResponse().setResponseCode(503) }
        Environment.sendBeacon = BeaconClient::send
        val tracker = Tracker("ad-1", listOf(entry("impression", server.url("/events/ad-1/impression").toString())))
        tracker.fire("impression")
        await { requests("/events/ad-1/impression").isNotEmpty() }
        settle()
        tracker.fire("impression")
        settle()
        assertEquals("fired once, whatever happened to it", 1, requests("/events/ad-1/impression").size)
    }

    // endregion
}

/** The Swift API client and logger suites' rules: the request, every way an answer maps to an error, the log. */
@RunWith(RobolectricTestRunner::class)
class ParityNetworkTest : ParityHarness() {
    // region ApiClient

    private fun post(path: String = "ping", body: JsonObject = buildJsonObject { put("hello", "there") }) = runBlocking { ApiClient(baseUrl).post(path, body) }

    private inline fun <reified T : ApiException> postFailing(): T = try {
        post()
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    } catch (error: ApiException) {
        error as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $error")
    }

    @Test fun postsJsonToTheV2Route() {
        answer("/v2/ping", """{"ok": true}""")
        assertEquals("""{"ok":true}""", post().toString())
        val request = received.single()
        assertEquals("/v2/ping", request.path)
        assertEquals("POST", request.method)
        assertEquals("application/json", request.contentType)
        assertEquals("there", request.json!!.str("hello"))
        assertEquals(10_000, ApiClient.DEFAULT_TIMEOUT_MILLIS)
    }

    @Test fun noHttpCacheAnswersARequest() {
        val consulted = mutableListOf<URI>()
        ResponseCache.setDefault(object : ResponseCache() {
            override fun get(uri: URI, method: String, headers: MutableMap<String, MutableList<String>>): CacheResponse? {
                consulted += uri
                return null
            }

            override fun put(uri: URI, connection: URLConnection): CacheRequest? = null
        })
        answer("/v2/ping", """{"ok": true}""")
        post()
        assertTrue("the cache is never asked: $consulted", consulted.isEmpty())
    }

    @Test fun aClientErrorCarriesTheServersErrorBody() {
        answer("/v2/ping", """{"errors": [{"status": "400", "code": "invalidRequest", "title": "video.id is required"}]}""", 400)
        val error = postFailing<ApiException.Http>()
        assertEquals(400, error.status)
        assertEquals("invalidRequest", error.errors.single().code)
        assertEquals("video.id is required", error.errors.single().title)
        assertEquals("HTTP 400: video.id is required", error.message)
    }

    @Test fun anErrorBodyTheClientCannotReadStillGivesTheStatus() {
        val bodies = listOf(
            Triple("{}", 400, "{}"),
            Triple("errors of the wrong type", 400, """{"errors": "nope"}"""),
            Triple("an item with nothing in it", 400, """{"errors": [{}]}"""),
            Triple("an HTML gateway page", 502, "<html><body>Bad Gateway</body></html>"),
        )
        for ((name, status, body) in bodies) {
            answer("/v2/ping", body, status)
            val error = postFailing<ApiException.Http>()
            assertEquals(name, status, error.status)
            assertEquals(name, "HTTP $status", error.message)
        }
    }

    @Test fun serverErrorsAndRateLimitsAreHttpErrorsWithTheirStatus() {
        for (status in listOf(500, 503, 429)) {
            answer("/v2/ping") { MockResponse().setResponseCode(status) }
            val error = postFailing<ApiException.Http>()
            assertEquals(status, error.status)
            assertTrue("no body, no parsed errors", error.errors.isEmpty())
        }
    }

    @Test fun aTransportFailureIsATransportError() {
        answer("/v2/ping") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val error = postFailing<ApiException.Transport>()
        assertTrue(error.message, error.message!!.startsWith("transport error"))
    }

    @Test fun unexpectedJsonIsADecodingError() {
        answer("/v2/ping", "not json at all")
        postFailing<ApiException.Decoding>()
    }

    @Test fun anEmptyBodyIsAnEmptyAnswer() {
        answer("/v2/debug") { MockResponse().setResponseCode(204) }
        assertNull(post("debug"))
    }

    @Test fun aCallerCancelledBeforePostSendsNothing() = runBlocking {
        val queued = java.util.ArrayDeque<Runnable>()
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            // Cancelled and then calling `post` with no suspension between: only `post`'s own check can stop it.
            coroutineContext.cancel()
            ApiClient(baseUrl, 10_000, queued::add).post("ping", buildJsonObject {})
        }
        val result = runCatching { call.await() }
        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue("nothing handed to the I/O worker", queued.isEmpty())
        assertEquals("nothing left the device", 0, server.requestCount)
    }

    // endregion

    // region Logger

    @Test fun aDisabledLevelNeverBuildsItsMessageAndAnEnabledOneBuildsItOnce() {
        var built = 0
        fun message(): String {
            built += 1
            return "m"
        }
        try {
            Logger.level = Configuration.LogLevel.WARNING
            Logger.debug(::message)
            Logger.info(::message)
            assertEquals("debug and info are off at WARNING", 0, built)
            Logger.warning(::message)
            assertEquals(1, built)
            Logger.level = Configuration.LogLevel.NONE
            Logger.error(::message)
            assertEquals("NONE builds nothing", 1, built)
            Logger.level = Configuration.LogLevel.DEBUG
            built = 0
            Logger.error(::message)
            Logger.warning(::message)
            Logger.info(::message)
            Logger.debug(::message)
            assertEquals("each message once, every level on", 4, built)
        } finally {
            Logger.level = Configuration.LogLevel.WARNING
        }
    }

    // endregion
}

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
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, detail = "x")
        assertEquals("reportErrors left out is off", 0, ErrorReporter.bufferedCount)
        answers.remove("/v2/config")
        initialize("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, detail = "x")
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
        assertEquals("config", report.str("stage"))
        assertEquals("network", report.str("detail"))
        assertTrue("${report["underlying"]}", report["underlying"]!!.jsonObject.str("domain")!!.startsWith("java."))
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
        assertEquals("request", report.str("stage"))
        assertNotNull(ads.lastRequestId)
        assertEquals("the id the app can quote", ads.lastRequestId, report.str("requestId"))
        assertEquals(ads.sessionId, report.str("sessionId"))
        assertEquals("no_ad", report.str("recovery"))
        val moment = report["history"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == "ads.request" }
        val createdAt = moment.str("createdAt")!!
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
        KontextVideo.user = User {
            id = "user-secret-1"
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
        assertFalse(body.str("batchId").isNullOrEmpty())
        assertEquals(0, body["dropped"]!!.jsonPrimitive.int)
    }

    @Test fun aBatchWhoseRequestFailsInTransitIsRetriedUnchanged() {
        var calls = 0
        answer("/v2/error") {
            calls += 1
            if (calls == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setResponseCode(204)
        }
        SdkInitializer.recordErrorReporting(true)
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, adId = "ad-1", detail = "start")
        await { requests("/v2/error").size >= 2 }
        settle()
        val bodies = requests("/v2/error").map { it.text }
        assertEquals("delivered once after the failure: $bodies", 2, bodies.size)
        assertEquals("the same batch, byte for byte, so the server counts it once", bodies[0], bodies[1])
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
        assertEquals("playback", process.str("stage"))
        assertEquals("ad_ended_failed", process.str("recovery"))
        assertEquals(terminated.id, process.str("adId"))
        assertEquals(ads.sessionId, process.str("sessionId"))
        val creative = errorReports("ad.creative_failed").single()
        assertEquals(405, creative["vastCode"]!!.jsonPrimitive.int)
        assertEquals("failed while waiting to be shown", "load", creative.str("stage"))
        assertEquals("ad_dropped_before_show", creative.str("recovery"))
    }

    @Test fun aPageThatBreaksTheMessageContractIsReportedOncePerAd() {
        SdkInitializer.recordErrorReporting(true)
        val ad = pause()
        val bridge = ad.loadedBridge!!
        val origin = Origin.of(ad.payload.frameUrl)
        bridge.receive("""{"adId":"someone-else","type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"adId":"${ad.id}","type":"ad.fromTheFuture"}""", origin, true) // a newer page: not a violation
        bridge.receive("""{"adId":"x"}""", Origin.of("https://creative.test"), false)
        await { errorReports("frame.protocol_violation").isNotEmpty() }
        settle()
        val violations = errorReports("frame.protocol_violation")
        assertEquals("$violations", 1, violations.size)
        assertEquals("envelope", violations.single().str("detail"))
    }

    @Test fun aLinearScreenThatNeverStartsIsReported() {
        SdkInitializer.recordErrorReporting(true)
        VideoAd.showTimeoutMillis = 300
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ad = linear()
        ad.show(host) // the screen is asked for and never created
        await { errorReports("ad.presentation_failed").isNotEmpty() }
        val report = errorReports("ad.presentation_failed").single()
        assertEquals("ad_ended_failed", report.str("recovery"))
        assertEquals("presentation", report.str("stage"))
    }

    @Test fun aNoFillReasonTheSdkDoesNotKnowNeverLeavesTheDevice() {
        initialize(reportsOn)
        val free = "contact secret@example.com at https://evil.test/x"
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", noFill = free)).toString())
        format(owner(), AdFormat.PAUSE)
        ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, detail = "start") // any fault after it carries the history
        await { errorReports("ad.creative_failed").isNotEmpty() }
        val raw = requests("/v2/error").last().text
        assertFalse("the server's free text stays on the device: $raw", raw.contains("secret@example.com") || raw.contains("evil.test"))
        val reasons = errorReports("ad.creative_failed").single()["history"]!!.jsonArray.mapNotNull { it.jsonObject["state"]?.jsonObject?.str("reason") }
        assertTrue("an unknown reason is \"other\": $reasons", reasons.contains("other"))
    }

    @Test fun everyStringFieldIsCappedByBytesEvenOneCharacterOfThousandsOfMarks() {
        val heavy = "e" + "́".repeat(5_000) // one character on screen, about 10 KB
        assertTrue(heavy.toByteArray().size > 10_000)
        assertTrue(ReportFormat.capped(heavy).toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
        assertEquals("short", ReportFormat.capped("short"))
        val long = "a".repeat(10_000)
        DebugReporter.event(heavy, adId = heavy, state = mapOf("format" to heavy))
        SdkInitializer.recordErrorReporting(true)
        repeat(20) { index ->
            ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, requestId = long, sessionId = heavy, adId = "$index$long", detail = heavy, underlying = ErrorReport.Underlying(long, 0))
        }
        await { errorReports().size == 20 }
        for (report in errorReports()) {
            for (field in listOf("requestId", "sessionId", "adId", "detail")) {
                assertTrue("$field capped", report.str(field)!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            }
            assertTrue(report["underlying"]!!.jsonObject.str("domain")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            val moment = report["history"]!!.jsonArray.single().jsonObject
            assertTrue(moment.str("name")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            assertTrue(moment.str("adId")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
            assertTrue(moment["state"]!!.jsonObject.str("format")!!.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
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
        assertEquals("no_ad", errorReports("ads.request_failed").single().str("recovery"))
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
        val underlying = errorReports("frame.load_failed").single()["underlying"]!!.jsonObject
        assertEquals("webview", underlying.str("domain"))
        assertEquals("-2", underlying["code"].toString())
    }

    @Test fun integrationMistakesAreReportedOnceTheServerAllowsIt() {
        SdkInitializer.resetForTesting()
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
        DebugReporter.event("ads.request", state = mapOf("format" to "pause", "playhead" to 1200, "filled" to true))
        DebugReporter.event("ad.loaded", adId = "ad-1", level = DebugReporter.Level.WARNING, state = mapOf("durationMs" to 842))
        await { debugBatches("ad.loaded").isNotEmpty() }
        val body = requests("/v2/debug").last().json!!
        assertEquals("test-token", body.str("publisherToken"))
        assertEquals(DeviceInfo.installId(Environment.context!!), body.str("installId"))
        assertEquals("sdk-kotlin-video", body["sdk"]!!.jsonObject.str("name"))
        assertTrue("config.response", debugNames().contains("config.response"))
        val events = debugBatches().flatten()
        val request = events.first { it.str("name") == "ads.request" }
        val createdAt = request.str("createdAt")!!
        assertTrue("ISO 8601 with milliseconds, UTC: $createdAt", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(createdAt))
        assertNull(request["adId"])
        val state = request["state"]!!.jsonObject
        assertEquals("pause", state.str("format"))
        assertEquals(1200.0, state["playhead"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("true", state["filled"]!!.jsonPrimitive.content)
        assertFalse(state["filled"]!!.jsonPrimitive.isString)
        val loaded = events.first { it.str("name") == "ad.loaded" }
        assertEquals("ad-1", loaded.str("adId"))
        assertEquals("warning", loaded.str("level"))
    }

    @Test fun nothingIsSentWhenConfigDoesNotAsk() {
        initialize()
        DebugReporter.event("ads.request", state = mapOf("format" to "pause"))
        settle()
        assertTrue(requests("/v2/debug").isEmpty())
    }

    @Test fun eventsAreCoalescedIntoOneRequestPerInterval() {
        SdkInitializer.recordDebugReporting(true)
        repeat(20) { DebugReporter.event("tick", state = mapOf("i" to it)) }
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
        KontextVideo.user = User {
            id = "canary-user-id"
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
            repeat(5) { DebugReporter.event("later", state = mapOf("i" to it)) }
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
            repeat(DebugReporter.MAX_BUFFERED + 10) { DebugReporter.event("tick", state = mapOf("i" to it)) }
            gate.countDown()
            await { debugBatches("tick").isNotEmpty() }
        } finally {
            gate.countDown()
        }
        val batch = debugBatches("tick").single()
        assertEquals(DebugReporter.MAX_BUFFERED, batch.size)
        assertEquals("the oldest ten were dropped", 10, batch.first()["state"]!!.jsonObject["i"]!!.jsonPrimitive.int)
    }

    @Test fun aNumberBeyondLongFormatsAsItselfWithoutATrap() {
        assertEquals("n a=1.0E30", DebugReporter.line("n", null, mapOf("a" to 1e30)))
        assertEquals("n a=-1.0E30", DebugReporter.line("n", null, mapOf("a" to -1e30)))
        assertEquals("n a=Infinity", DebugReporter.line("n", null, mapOf("a" to Double.POSITIVE_INFINITY)))
        assertEquals("n a=NaN", DebugReporter.line("n", null, mapOf("a" to Double.NaN)))
        assertEquals("n a=42 b=1.5 c=7", DebugReporter.line("n", null, mapOf("a" to 42.0, "b" to 1.5, "c" to 7)))
        assertEquals("config.response", DebugReporter.line("config.response", null, null))
        DebugReporter.event("n", state = mapOf("a" to Double.POSITIVE_INFINITY)) // the console line is formatted whether or not reporting is on
    }

    @Test fun theLoadFlowReportsItsEvents() {
        initialize(debugOn)
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ad = format(owner(), AdFormat.PAUSE).getOrThrow()
        var loaded: Result<Unit>? = null
        Environment.scope.launch { loaded = runCatching { ad.load() } }
        ad.loadedBridge!!.receive("""{"adId":"${ad.id}","type":"ad.loaded"}""", Origin.of(ad.payload.frameUrl), true)
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
