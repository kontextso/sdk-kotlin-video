package so.kontext.video.internal.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * The HTTP mechanics the SDK's two clients share: open a connection, send, follow redirects by the caller's
 * [Redirects] policy, read the status and, when asked, the body up to a cap; always disconnect. Blocking: the
 * clients run it off the main thread. It knows nothing of JSON, ads or beacons:
 * - [ApiClient]: Kontext's server, a JSON POST whose answer is read and parsed.
 * - [BeaconClient]: anyone's tracking URL, a GET whose status is all that is read.
 *
 * Redirects are followed here, never by `HttpURLConnection`: it does not follow one that changes protocol (`http`
 * to `https` or back), and it would replay a POST differently from iOS's `URLSession`. Each hop is one connection.
 */
internal object HttpTransport {
    /**
     * The most redirects any request follows, the SDK's own and beacons alike: tracking chains hand a beacon on
     * through several servers, and our server could move behind another host.
     */
    const val MAX_REDIRECTS = 10

    /** The statuses that redirect; a 307 or 308 keeps the method and body, the others continue as a GET. */
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private val KEEP_METHOD = setOf(307, 308)

    /** Only web addresses are followed: never a redirect to another scheme (`file:`, an app's own). */
    private val FOLLOWED_SCHEMES = setOf("http", "https")

    /** How much of a body is read at a time. */
    private const val BUFFER_BYTES = 16 * 1024

    /**
     * How redirects are followed: at most [MAX_REDIRECTS]; only to http or https; from https down to http only
     * when [allowsDowngrade]. A 307 or 308 sends the request again as it was, body and all; a 301, 302 or 303
     * continues as a GET.
     */
    class Redirects(val allowsDowngrade: Boolean)

    /** The final answer: its status, and its body when one was asked for (`null` otherwise). */
    class Answer(val status: Int, val body: String?)

    /**
     * Sends a POST of [body] to [start], or a GET when [body] is `null`, and follows redirects by [redirects].
     * [maxBodyBytes]: the final body is read up to it ([ResponseTooLarge] past it); `null` reads no body at all, and
     * disconnecting drops whatever the server still sends. [current] holds the open connection, so a caller can
     * disconnect it from another thread to stop a blocked read; [isActive] is asked before each hop.
     */
    fun exchange(
        start: URL,
        body: ByteArray?,
        redirects: Redirects,
        timeoutMillis: Int,
        maxBodyBytes: Long?,
        current: AtomicReference<HttpURLConnection?>? = null,
        isActive: () -> Boolean = { true },
    ): Answer {
        var url = start
        var post = body != null
        repeat(MAX_REDIRECTS + 1) {
            if (!isActive()) throw IOException("cancelled")
            val connection = url.openConnection() as HttpURLConnection
            current?.set(connection)
            try {
                send(connection, if (post) body else null, timeoutMillis)
                val status = connection.responseCode
                val next = nextHop(connection, status, url, redirects)
                if (next == null) {
                    if (maxBodyBytes == null) return Answer(status, null)
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    if (connection.contentLengthLong > maxBodyBytes) throw ResponseTooLarge()
                    return Answer(status, stream?.use { readCapped(it, maxBodyBytes) } ?: "")
                }
                if (status !in KEEP_METHOD) post = false
                url = next
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("more than $MAX_REDIRECTS redirects")
    }

    /** Sets [connection] up and sends the request: a POST of [body], or a GET when it is `null`. */
    private fun send(connection: HttpURLConnection, body: ByteArray?, timeoutMillis: Int) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.useCaches = false
        if (body == null) {
            connection.requestMethod = "GET"
            return
        }
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
    }

    /** Where a redirect answer points, when [redirects] lets it be followed; `null` when the answer is the last. */
    private fun nextHop(connection: HttpURLConnection, status: Int, url: URL, redirects: Redirects): URL? =
        connection.getHeaderField("Location")?.takeIf { status in REDIRECTS }
            ?.let { runCatching { URL(url, it) }.getOrNull() }
            ?.takeIf { it.protocol in FOLLOWED_SCHEMES }
            ?.takeIf { redirects.allowsDowngrade || url.protocol != "https" || it.protocol == "https" }

    /** The body as UTF-8, reading at most one byte past [maxBytes]: a longer one fails without being read whole. */
    private fun readCapped(stream: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes + 1L - out.size()).toInt())
            if (read == -1) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw ResponseTooLarge()
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
