package so.kontext.video.internal.network

import so.kontext.video.VideoAdException.RequestFailure
import so.kontext.video.internal.payloads.ErrorResponse
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException

/** Why a request to the ad server failed. */
internal sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The request never got an answer: no network, timeout, DNS. */
    class Transport(cause: IOException) : ApiException("transport error: ${cause.javaClass.simpleName}", cause)

    /** The server answered with an error status; [errors] is its error body, when it sent one. */
    class Http(val status: Int, val errors: List<ErrorResponse.Item>) :
        ApiException("HTTP $status" + errors.mapNotNull { it.summary }.let { if (it.isEmpty()) "" else ": " + it.joinToString("; ") })

    /** The answer was not the JSON we expected. */
    class Decoding(cause: Throwable) : ApiException("decoding error: ${cause.message}", cause)
}

/** The answer is over [ApiClient.MAX_BODY_BYTES]: never read whole, so an answer the SDK cannot use. */
internal class ResponseTooLarge : IOException("the answer is over ${ApiClient.MAX_BODY_BYTES} bytes")

/** The exchange threw something other than an `IOException`: a request the SDK could not complete as the contract says. */
internal class UnexpectedFailure(cause: Throwable) : IOException(cause)

/**
 * What the app hears of what an ads request threw: a timeout is [RequestFailure.TimedOut]; an answer over the size
 * limit, a status line that is not HTTP or an exchange that threw something unexpected is
 * [RequestFailure.BadResponse]; every other I/O failure is [RequestFailure.Offline]. An error status is
 * [RequestFailure.Server]; an undecodable answer, and anything else, [RequestFailure.BadResponse].
 */
internal fun Throwable?.toRequestFailure(): RequestFailure = when (this) {
    is ApiException.Transport -> cause.toRequestFailure()
    is ApiException.Http -> if (status > 0) RequestFailure.Server(status) else RequestFailure.BadResponse
    is SocketTimeoutException -> RequestFailure.TimedOut
    is ResponseTooLarge, is UnexpectedFailure, is ProtocolException -> RequestFailure.BadResponse
    is IOException -> RequestFailure.Offline
    else -> RequestFailure.BadResponse
}

/** The case's name without its status, as the debug events carry it: `offline`, `timedOut`, `server`, `badResponse`, `missingUser`. */
internal val RequestFailure.kind: String
    get() = if (this is RequestFailure.Server) "server" else toString()
