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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executor
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
     * which ends the blocked read: moving blocking I/O to another thread does not make it cancellable.
     */
    suspend fun post(path: String, body: JsonObject): JsonElement? {
        coroutineContext.ensureActive() // a caller cancelled before this point sends nothing
        val bytes = body.toString().toByteArray()
        val connection = try {
            URL(baseUrl.trimEnd('/') + "/v2/" + path).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw ApiException.Transport(error)
        }
        val (status, text) = try {
            withTimeout(timeoutMillis.toLong()) { exchange(connection, bytes) }
        } catch (_: TimeoutCancellationException) {
            throw ApiException.Transport(SocketTimeoutException("no complete answer within $timeoutMillis ms"))
        }
        if (status !in 200..299) throw ApiException.Http(status, parseErrors(text))
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ApiException.Decoding(error)
        }
    }

    /** The request on an I/O thread; cancelling the caller disconnects, which unblocks that thread at once. */
    private suspend fun exchange(connection: HttpURLConnection, bytes: ByteArray): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            executor.execute {
                // Queued behind other work while the caller was cancelled: nothing is sent at all. (A disconnect
                // before the connection exists would not stop the connect that follows.)
                if (!continuation.isActive) return@execute
                val result = try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMillis
                    connection.readTimeout = timeoutMillis
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setRequestProperty("Content-Type", "application/json")
                    // A streamed body cannot be replayed: without it the HTTP stack silently sends the POST again
                    // when the connection drops before the answer, and one attempt becomes two.
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    Result.success(status to (stream?.bufferedReader()?.use { it.readText() } ?: ""))
                } catch (error: IOException) {
                    Result.failure(ApiException.Transport(error))
                } finally {
                    connection.disconnect()
                }
                // A cancelled caller has gone already; its result is dropped.
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /** The JSON:API-shaped error body; an item that is not an object fails the whole body, which then counts as none. */
        fun parseErrors(text: String): List<ErrorItem> = runCatching {
            Json.parseToJsonElement(text).obj()?.get("errors")?.arr()?.map { element ->
                val item = element.obj() ?: error("not an object")
                ErrorItem(item.str("status"), item.str("code"), item.str("title"), item.str("detail"))
            }
        }.getOrNull() ?: emptyList()
    }
}
