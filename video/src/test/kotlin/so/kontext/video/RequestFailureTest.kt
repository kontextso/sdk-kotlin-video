package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import so.kontext.video.VideoAdException.RequestFailure
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.ResponseTooLarge
import so.kontext.video.internal.network.UnexpectedFailure
import so.kontext.video.internal.network.toRequestFailure
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Why a request failed, as the Swift suite has it: four reasons an app can act on, mapped from the transport,
 * the HTTP status and the answer; the underlying exception stays out of the public API.
 */
class RequestFailureTest {
    private fun transport(error: IOException) = ApiException.Transport(error).toRequestFailure()

    @Test fun aTimeoutIsTimedOut() {
        assertEquals(RequestFailure.TimedOut, transport(SocketTimeoutException("read timed out")))
        assertEquals("a bare one, the same", RequestFailure.TimedOut, SocketTimeoutException().toRequestFailure())
    }

    @Test fun everyOtherWayOfNotGettingThroughIsOffline() {
        val offline = listOf(
            UnknownHostException("no such host"),
            ConnectException("refused"),
            NoRouteToHostException(),
            SSLException("captive portal"),
            SSLHandshakeException("untrusted"),
            SocketException("reset"),
            IOException("lost"),
        )
        for (error in offline) {
            assertEquals(error.javaClass.simpleName, RequestFailure.Offline, transport(error))
            assertEquals("a bare ${error.javaClass.simpleName}, the same", RequestFailure.Offline, error.toRequestFailure())
        }
    }

    @Test fun everyErrorStatusIsAServerFailureWithItsStatus() {
        for (status in listOf(400, 404, 410, 422, 429, 500, 502, 503, 504, 302)) {
            assertEquals("$status", RequestFailure.Server(status), ApiException.Http(status, emptyList()).toRequestFailure())
        }
        assertEquals("not an HTTP answer", RequestFailure.BadResponse, ApiException.Http(-1, emptyList()).toRequestFailure())
    }

    @Test fun anAnswerTheSdkCannotUseIsABadResponse() {
        assertEquals(RequestFailure.BadResponse, ApiException.Decoding(IllegalArgumentException("not JSON")).toRequestFailure())
        assertEquals("over the size limit", RequestFailure.BadResponse, transport(ResponseTooLarge()))
        assertEquals("a status line that is not HTTP", RequestFailure.BadResponse, transport(ProtocolException("Unexpected status line")))
        assertEquals("anything else the exchange threw", RequestFailure.BadResponse, transport(UnexpectedFailure(IllegalArgumentException("port out of range"))))
        assertEquals("anything unknown", RequestFailure.BadResponse, IllegalStateException("?").toRequestFailure())
    }

    @Test fun theReasonsCompareByValueAndNameThemselves() {
        assertEquals(RequestFailure.Server(503), RequestFailure.Server(503))
        assertEquals(RequestFailure.Server(503).hashCode(), RequestFailure.Server(503).hashCode())
        assertNotEquals(RequestFailure.Server(503), RequestFailure.Server(500))
        assertNotEquals(RequestFailure.Offline as RequestFailure, RequestFailure.TimedOut as RequestFailure)
        assertEquals(
            listOf("offline", "timedOut", "server(503)", "badResponse"),
            listOf(RequestFailure.Offline, RequestFailure.TimedOut, RequestFailure.Server(503), RequestFailure.BadResponse).map { it.toString() },
        )
    }

    @Test fun theRequestExceptionCarriesItsReasonAndNoUnderlyingException() {
        val error = VideoAdException.Request(RequestFailure.Server(503))
        assertEquals(RequestFailure.Server(503), error.failure)
        assertEquals("request failed (server(503))", error.message)
        assertNull("the underlying exception is in the log, not the API", error.cause)
    }

    /** What an app writes: exhaustive without an else, so a new reason would be a compile error. */
    @Test fun anAppCanTellWhyARequestFailed() {
        fun advice(failure: RequestFailure): String = when (failure) {
            RequestFailure.Offline -> "when online"
            RequestFailure.TimedOut -> "later"
            is RequestFailure.Server -> "later (${failure.status})"
            RequestFailure.BadResponse -> "report"
        }
        val error: VideoAdException = VideoAdException.Request(RequestFailure.Server(503))
        assertEquals("later (503)", advice((error as VideoAdException.Request).failure))
    }
}
