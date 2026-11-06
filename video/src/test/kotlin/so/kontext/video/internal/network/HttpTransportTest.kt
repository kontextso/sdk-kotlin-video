package so.kontext.video.internal.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * The transport's redirect rules on their own: from https down to http only for a client that allows it, and only
 * the statuses that redirect. An https URL is served by the plain local server through a handler of its own, so
 * no certificate is needed; a hop to http leaves that handler.
 */
class HttpTransportTest {
    private val server = MockWebServer()

    @BeforeEach fun start() = server.start()

    @AfterEach fun stop() = server.shutdown()

    /** `https://secure.test<path>`, answered by the local server. */
    private fun secure(path: String): URL {
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = server.url(url.file).toUrl().openConnection()
        }
        return URL("https", "secure.test", 443, path, handler)
    }

    private fun get(start: URL, allowsDowngrade: Boolean) =
        HttpTransport.exchange(start, null, HttpTransport.Redirects(allowsDowngrade), timeoutMillis = 5_000, maxBodyBytes = 1_000)

    private fun redirect(status: Int, to: String) = MockResponse().setResponseCode(status).setHeader("Location", to)

    @Test fun fromHttpsDownToHttpOnlyWhenTheClientAllowsIt() {
        // The SDK's own requests (ApiClient) never leave https for http; beacons (BeaconClient) follow tracking chains that do.
        server.enqueue(redirect(302, server.url("/plain").toString()))
        val refused = get(secure("/start"), allowsDowngrade = false)
        assertEquals(302, refused.status, "the redirect itself is the answer")
        assertEquals(1, server.requestCount)

        server.enqueue(redirect(302, server.url("/plain").toString()))
        server.enqueue(MockResponse().setBody("ok"))
        val followed = get(secure("/start"), allowsDowngrade = true)
        assertEquals(200, followed.status)
        assertEquals("ok", followed.body)
        assertEquals(listOf("/start", "/start", "/plain"), List(3) { server.takeRequest().path })
    }

    @Test fun withinHttpsARedirectIsFollowedWhateverThePolicy() {
        for (allowsDowngrade in listOf(false, true)) {
            server.enqueue(redirect(301, "/moved"))
            server.enqueue(MockResponse().setBody("moved"))
            assertEquals("moved", get(secure("/start"), allowsDowngrade).body, "allowsDowngrade=$allowsDowngrade")
        }
    }

    @Test fun onlyTheRedirectStatusesAreFollowed() {
        for (status in listOf(200, 300, 304, 305, 306, 400)) {
            server.enqueue(redirect(status, "/elsewhere"))
            assertEquals(status, get(server.url("/start").toUrl(), allowsDowngrade = true).status, "$status")
        }
        assertEquals(6, server.requestCount, "nothing followed")
    }

    @Test fun aLocationThatIsNoUrlIsTheAnswer() {
        server.enqueue(redirect(302, "http://[broken"))
        assertEquals(302, get(server.url("/start").toUrl(), allowsDowngrade = true).status)
        assertEquals(1, server.requestCount)
    }

    @Test fun aBeaconReadsNoBody() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        val answer = HttpTransport.exchange(server.url("/b").toUrl(), null, HttpTransport.Redirects(true), 5_000, maxBodyBytes = null)
        assertEquals(200, answer.status)
        assertEquals(null, answer.body)
    }
}
