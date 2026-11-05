package so.kontext.video.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import so.kontext.video.internal.payloads.ErrorResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Sends JSON requests to the ad server's `/v2/` routes, off the main thread, through [HttpTransport]. */
internal class ApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    /** Where the blocking exchange runs; tests pass their own to see the worker start and finish. */
    private val executor: Executor = Dispatchers.IO.asExecutor(),
) {
    /**
     * POSTs [body] to `v2/[path]` and returns the parsed answer, or `null` for an empty body. [timeoutMillis]
     * bounds the whole call, connection to last byte: the socket timeouts alone are idle timeouts, and a server
     * that trickles bytes would hold the request as long as it likes. A cancelled caller closes the connection,
     * which ends the blocked read: moving blocking I/O to another thread does not make it cancellable. A body
     * over [MAX_BODY_BYTES] is a transport error, read no further; the JSON is parsed on the I/O thread.
     */
    suspend fun post(path: String, body: JsonObject): JsonElement? {
        coroutineContext.ensureActive() // a caller cancelled before this point sends nothing
        val bytes = body.toString().toByteArray()
        val url = try {
            URL(baseUrl.trimEnd('/') + "/v2/" + path)
        } catch (error: IOException) {
            throw ApiException.Transport(error)
        }
        return try {
            withTimeout(timeoutMillis.toLong()) { exchange(url, bytes) }
        } catch (_: TimeoutCancellationException) {
            throw ApiException.Transport(SocketTimeoutException("no complete answer within $timeoutMillis ms"))
        }
    }

    /**
     * The request on an I/O thread; cancelling the caller disconnects, which unblocks that thread at once.
     * Whatever the exchange throws comes back to the caller as an [ApiException]: an exception left on the
     * worker would kill the app, and the caller would wait for the timeout.
     */
    private suspend fun exchange(url: URL, bytes: ByteArray): JsonElement? =
        suspendCancellableCoroutine { continuation ->
            val current = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation { current.get()?.disconnect() }
            executor.execute {
                // Queued behind other work while the caller was cancelled: nothing is sent at all. (A disconnect
                // before the connection exists would not stop the connect that follows.)
                if (!continuation.isActive) return@execute
                val result = try {
                    val answer = HttpTransport.exchange(url, bytes, REDIRECT_POLICY, timeoutMillis, MAX_BODY_BYTES.toLong(), current) {
                        continuation.isActive
                    }
                    Result.success(answer(answer.status, answer.body.orEmpty()))
                } catch (error: ApiException) {
                    Result.failure(error)
                } catch (error: IOException) {
                    Result.failure(ApiException.Transport(error))
                } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                    // A redirect to a port out of range, a revoked network permission, a TLS provider's own
                    // runtime exception, an answer too big for memory: the request failed, the app goes on.
                    Result.failure(ApiException.Transport(UnexpectedFailure(error)))
                }
                // A cancelled caller has gone already; its result is dropped.
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    /** Parses the body where it was read, off the main thread: a large answer must not stall the app's UI. */
    private fun answer(status: Int, text: String): JsonElement? {
        if (status !in 200..299) throw ApiException.Http(status, ErrorResponse.parse(text))
        if (text.isBlank()) return null
        return parseAnswer(text)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /**
         * The largest body read, an answer or an error: far above any real one, far below what a low-end
         * device can hold. Beyond it the request is a transport error: the SDK never received the answer
         * whole, as when the connection drops.
         */
        const val MAX_BODY_BYTES = 5 * 1024 * 1024

        /**
         * Redirects as the Swift SDK's URLSession follows them: a 307 or 308 sends the POST again, body and all; a 301,
         * 302 or 303 continues as a GET. At most `HttpTransport.MAX_REDIRECTS`, never from https down to http (it is our own server).
         * Deliberate: the routes do not redirect today, so a redirect would be our own server moving, and refusing it
         * would be an outage of every installed SDK.
         */
        private val REDIRECT_POLICY = HttpTransport.Redirects(allowsDowngrade = false)
    }
}
