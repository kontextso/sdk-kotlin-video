package so.kontext.video.internal.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.ResponseTooLarge
import so.kontext.video.internal.network.UnexpectedFailure
import so.kontext.video.internal.payloads.AdPayload

/** One error report's vocabulary: a failure's domain that survives R8, and a failed request as the report's fields. */
class ErrorReportTest {
    @Test fun aReportsDomainReadsTheSameInEveryBuild() {
        val report = ErrorReport
        assertEquals("too_large", report.domainOf(ResponseTooLarge()))
        assertEquals("unexpected", report.domainOf(UnexpectedFailure(IllegalStateException())))
        assertEquals("json", report.domainOf(kotlinx.serialization.SerializationException("x")))
        assertEquals("java.net.SocketTimeoutException", report.domainOf(java.net.SocketTimeoutException()))
        assertEquals("other", report.domainOf(object : RuntimeException() {}))
        assertEquals("malformed_field", report.domainOf(AdPayload.MalformedField("price.value", "x")))
        assertEquals("javax.net.ssl.SSLHandshakeException", report.domainOf(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals("android.os.NetworkOnMainThreadException", report.domainOf(android.os.NetworkOnMainThreadException()))
        assertEquals("other", report.domainOf(ApiException.Decoding(IllegalStateException())), "the SDK's own: renamed by R8")
    }

    @Test fun aFailedRequestIsItsStatusOrItsCause() {
        // error → httpStatus, underlying, detail
        val table = listOf(
            ApiException.Http(503, emptyList()) to Triple(503, null, null),
            ApiException.Transport(java.net.SocketTimeoutException()) to
                Triple(null, ErrorReport.Underlying("java.net.SocketTimeoutException", 0), "network"),
            ApiException.Transport(ResponseTooLarge()) to Triple(null, ErrorReport.Underlying("too_large", 0), "network"),
            ApiException.Decoding(AdPayload.MalformedField("id", "x")) to Triple(null, ErrorReport.Underlying("malformed_field", 0), null),
            ApiException.Decoding(kotlinx.serialization.SerializationException("x")) to Triple(null, ErrorReport.Underlying("json", 0), null),
            IllegalStateException() to Triple(null, ErrorReport.Underlying("java.lang.IllegalStateException", 0), null),
        )
        for ((error, expected) in table) {
            val request = ErrorReport.FailedRequest(error)
            assertEquals(expected, Triple(request.httpStatus, request.underlying, request.detail), "$error")
        }
    }
}
