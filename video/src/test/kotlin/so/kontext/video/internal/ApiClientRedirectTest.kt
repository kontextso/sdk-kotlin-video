package so.kontext.video.internal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.HttpTransport

/** Redirects are followed as the Swift SDK follows them: 307 and 308 keep the POST and its body, 301 to 303 become a GET. */
class ApiClientRedirectTest {
    private val server = MockWebServer()
    private val other = MockWebServer()
    private val body = buildJsonObject { put("token", JsonPrimitive("t-1")) }

    @BeforeEach fun start() {
        server.start()
        other.start()
    }

    @AfterEach fun stop() {
        server.shutdown()
        other.shutdown()
    }

    private fun post() = runBlocking { ApiClient(server.url("/").toString()).post("config", body) }

    private fun redirect(status: Int, to: String) = MockResponse().setResponseCode(status).setHeader("Location", to)

    @Test fun a307Or308SendsThePostAgainWithItsBody() {
        for (status in listOf(307, 308)) {
            server.enqueue(redirect(status, other.url("/v2/moved").toString()))
            other.enqueue(MockResponse().setBody("""{"enabled": true}"""))
            val answer = post()
            assertEquals("true", answer!!.jsonObject["enabled"]!!.jsonPrimitive.content)
            assertEquals("POST", server.takeRequest().method)
            val moved = other.takeRequest()
            assertEquals("POST", moved.method, "$status keeps the method")
            assertEquals("/v2/moved", moved.path)
            assertEquals(body.toString(), moved.body.readUtf8(), "$status keeps the body")
        }
    }

    @Test fun a301To303ContinuesAsAGetWithoutTheBody() {
        for (status in listOf(301, 302, 303)) {
            server.enqueue(redirect(status, "/v2/elsewhere")) // relative, on the same server
            server.enqueue(MockResponse().setBody("""{"enabled": true}"""))
            post()
            assertEquals("POST", server.takeRequest().method)
            val moved = server.takeRequest()
            assertEquals("GET", moved.method, "$status becomes a GET")
            assertEquals("/v2/elsewhere", moved.path)
            assertEquals(0L, moved.bodySize)
        }
    }

    @Test fun moreThanMaxRedirectsFailAsATransportError() {
        repeat(HttpTransport.MAX_REDIRECTS + 1) { server.enqueue(redirect(307, "/v2/again")) }
        assertThrows<ApiException.Transport> { post() }
        assertEquals(HttpTransport.MAX_REDIRECTS + 1, server.requestCount, "the first request and MAX_REDIRECTS hops, no more")
    }

    @Test fun aRedirectWithoutATargetIsTheAnswer() {
        server.enqueue(MockResponse().setResponseCode(307))
        val error = assertThrows<ApiException.Http> { post() }
        assertEquals(307, error.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun aRedirectToAnotherSchemeIsTheAnswerAndOpensNothing() {
        for (target in listOf("file:///does-not-need-to-exist", "ftp://files.test/x", "intent://x")) {
            server.enqueue(redirect(307, target))
            val error = assertThrows<ApiException.Http>(target) { post() }
            assertEquals(307, error.status, target)
        }
        assertEquals(3, server.requestCount, "one request each, nothing followed")
    }

    @Test fun aRedirectChainEndsWithTheLastAnswer() {
        server.enqueue(redirect(308, "/v2/one"))
        server.enqueue(redirect(302, "/v2/two"))
        server.enqueue(MockResponse().setResponseCode(503))
        val error = assertThrows<ApiException.Http> { post() }
        assertEquals(503, error.status)
        assertEquals(listOf("POST", "POST", "GET"), List(3) { server.takeRequest().method })
    }
}
