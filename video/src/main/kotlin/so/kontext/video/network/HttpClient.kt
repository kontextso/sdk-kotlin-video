package so.kontext.video.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResponse(val statusCode: Int, val body: String)

internal class HttpException(val statusCode: Int) : IOException("HTTP $statusCode")

/** JSON `POST` over `HttpURLConnection`; the blocking I/O runs on `Dispatchers.IO`. */
internal object HttpClient {
    suspend fun postJson(url: String, body: String, timeoutMs: Long): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs.toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
