package so.kontext.video.internal

import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.robolectric.Shadows.shadowOf
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.platform.Environment
import java.net.ResponseCache
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue

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
        // Every ads request names the viewer.
        so.kontext.video.internal.state.SdkState.current.recordUser(so.kontext.video.KontextVideo.User("test-user"))
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
        so.kontext.video.internal.state.SdkState.current.recordUser(KontextVideo.User("test-user"))
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
