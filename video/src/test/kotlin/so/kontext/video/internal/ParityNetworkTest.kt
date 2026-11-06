package so.kontext.video.internal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import java.net.CacheRequest
import java.net.CacheResponse
import java.net.ResponseCache
import java.net.URI
import java.net.URLConnection

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
