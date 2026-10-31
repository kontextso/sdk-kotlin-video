package so.kontext.video.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

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

/** Sends JSON requests to the ad server's `/v2/` routes, off the main thread. */
internal class ApiClient(private val baseUrl: String, private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS) {
    /** POSTs [body] to `v2/[path]` and returns the parsed answer, or `null` for an empty body. */
    suspend fun post(path: String, body: JsonObject): JsonElement? {
        coroutineContext.ensureActive() // a caller cancelled before this point sends nothing
        val bytes = body.toString().toByteArray()
        val (status, text) = withContext(Dispatchers.IO) {
            val connection = try {
                (URL(baseUrl.trimEnd('/') + "/v2/" + path).openConnection() as HttpURLConnection)
            } catch (error: IOException) {
                throw ApiException.Transport(error)
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.doOutput = true
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (error: IOException) {
                throw ApiException.Transport(error)
            } finally {
                connection.disconnect()
            }
        }
        if (status !in 200..299) throw ApiException.Http(status, parseErrors(text))
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ApiException.Decoding(error)
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
