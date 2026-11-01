package so.kontext.video.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Sends JSON requests to the ad server's `/v2/` routes, off the main thread. */
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
                    val (status, text) = follow(url, bytes, current) { continuation.isActive }
                    Result.success(answer(status, text))
                } catch (error: ApiException) {
                    Result.failure(error)
                } catch (error: IOException) {
                    Result.failure(ApiException.Transport(error))
                } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                    // A redirect to a port out of range, a revoked network permission, a TLS provider's own
                    // runtime exception, an answer too big for memory: the request failed, the app goes on.
                    Result.failure(ApiException.Transport(IOException(error)))
                }
                // A cancelled caller has gone already; its result is dropped.
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    /** Parses the body where it was read, off the main thread: a large answer must not stall the app's UI. */
    private fun answer(status: Int, text: String): JsonElement? {
        if (status !in 200..299) throw ApiException.Http(status, parseErrors(text))
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw ApiException.Decoding(error)
        }
    }

    /**
     * Sends the request and follows redirects as the Swift SDK's URLSession does: a 307 or 308 sends the POST
     * again, body and all; a 301, 302 or 303 continues as a GET. At most [MAX_REDIRECTS], only to http or
     * https, and never from https to http. Deliberate: the routes do not redirect today, so a redirect would be our own server
     * moving, and refusing it would be an outage of every installed SDK. Each hop is one connection whose body
     * is streamed, so the HTTP stack cannot send it again on its own when a connection drops.
     */
    private fun follow(start: URL, bytes: ByteArray, current: AtomicReference<HttpURLConnection?>, isActive: () -> Boolean): Pair<Int, String> {
        var url = start
        var post = true
        repeat(MAX_REDIRECTS + 1) {
            if (!isActive()) throw IOException("cancelled")
            val connection = url.openConnection() as HttpURLConnection
            current.set(connection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.useCaches = false
                if (post) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                } else {
                    connection.requestMethod = "GET"
                }
                val status = connection.responseCode
                val next = connection.getHeaderField("Location")?.takeIf { status in REDIRECTS }?.let { runCatching { URL(url, it) }.getOrNull() }
                // Only to http or https, and never down from https: anything else is the answer, not followed.
                if (next == null || next.protocol !in FOLLOWED_SCHEMES || (url.protocol == "https" && next.protocol != "https")) {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    if (connection.contentLengthLong > MAX_BODY_BYTES) throw IOException("the answer is over $MAX_BODY_BYTES bytes")
                    return status to (stream?.use(::readCapped) ?: "")
                }
                if (status !in KEEP_METHOD) post = false
                url = next
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("more than $MAX_REDIRECTS redirects")
    }

    /** The body as UTF-8, reading at most one byte past [MAX_BODY_BYTES]: a longer one fails without being read whole. */
    private fun readCapped(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BODY_BYTES + 1L - out.size()).toInt())
            if (read == -1) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) throw IOException("the answer is over $MAX_BODY_BYTES bytes")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /**
         * The largest body read, an answer or an error: far above any real one, far below what a low-end
         * device can hold. Beyond it the request is a transport error: the SDK never received the answer
         * whole, as when the connection drops.
         */
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        private const val BUFFER_BYTES = 16 * 1024
        const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val KEEP_METHOD = setOf(307, 308)
        private val FOLLOWED_SCHEMES = setOf("http", "https")

        /** The JSON:API-shaped error body; an item that is not an object fails the whole body, which then counts as none. */
        fun parseErrors(text: String): List<ErrorItem> = runCatching {
            Json.parseToJsonElement(text).obj()?.get("errors")?.arr()?.map { element ->
                val item = element.obj() ?: error("not an object")
                ErrorItem(item.str("status"), item.str("code"), item.str("title"), item.str("detail"))
            }
        }.getOrNull() ?: emptyList()
    }
}
