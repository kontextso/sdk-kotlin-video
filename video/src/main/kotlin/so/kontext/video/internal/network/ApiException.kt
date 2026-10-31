package so.kontext.video.internal.network

import java.io.IOException

/** Why a request to the ad server failed. */
internal sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The request never got an answer: no network, timeout, DNS. */
    class Transport(cause: IOException) : ApiException("transport error: ${cause.javaClass.simpleName}", cause)

    /** The server answered with an error status; [errors] is its error body, when it sent one. */
    class Http(val status: Int, val errors: List<ErrorItem>) :
        ApiException("HTTP $status" + errors.mapNotNull { it.summary }.let { if (it.isEmpty()) "" else ": " + it.joinToString("; ") })

    /** The answer was not the JSON we expected. */
    class Decoding(cause: Throwable) : ApiException("decoding error: ${cause.message}", cause)
}

/** One item of the server's error body; every field lenient. */
internal class ErrorItem(val status: String?, val code: String?, val title: String?, val detail: String?) {
    /** The item as one log phrase: `title`, else `detail`, else `code`. */
    val summary: String? get() = title ?: detail ?: code
}
