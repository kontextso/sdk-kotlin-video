package so.kontext.video.internal.network

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.platform.Environment
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends beacons: a GET of a tracking URL, off the main thread, once, with no retries, fire and forget. Only the
 * status is read, never the body. Which beacons fire and when is `Tracker`'s; this only delivers a finished URL.
 *
 * Each beacon has [timeoutMillis] in all, every redirect included: the socket timeouts are idle timeouts, and a
 * tracker that accepts the connection and then trickles its headers (or a chain of redirects, each with its own
 * timeouts) would hold a worker as long as it likes. Past the deadline the connection is closed, which ends the
 * blocked read, as `ApiClient` does. Beacons run on a few workers of their own, so a black-holed tracker cannot
 * take the threads the ads requests need.
 */
internal object BeaconClient {
    /** The whole exchange, every redirect included. A constant in spirit; tests shorten it. */
    @set:VisibleForTesting
    var timeoutMillis = 10_000

    /** At most this many beacons on the wire at once; the rest wait their turn. */
    private const val MAX_PARALLEL = 4

    /**
     * Redirects as iOS's `URLSession` follows them for a beacon: up to `HttpTransport.MAX_REDIRECTS`, `http` to `https` and back,
     * as tracking chains do; the app's network security config still decides whether cleartext http may go.
     */
    private val REDIRECT_POLICY = HttpTransport.Redirects(allowsDowngrade = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val executor = Dispatchers.IO.limitedParallelism(MAX_PARALLEL).asExecutor()

    /** Exchanges running on a worker now, for tests: a beacon past its deadline leaves none behind. */
    val inFlight = AtomicInteger()

    /** GETs [url]; [label] names it in the log (`ad f1a2b3c4: impression`). */
    fun send(url: String, label: String) {
        Environment.scope.launch {
            val status = runCatching { withTimeoutOrNull(timeoutMillis.toLong()) { exchange(url) } }
            status.onFailure { Logger.warning { "$label beacon failed, ${it.javaClass.simpleName}" } }
            val answer = status.getOrNull()
            when {
                status.isFailure -> Unit
                answer == null -> Logger.warning { "$label beacon gave up after ${timeoutMillis / 1000.0} s" }
                answer >= 400 -> Logger.warning { "$label beacon answered $answer" }
            }
        }
    }

    /** The status of [url]'s GET, on a beacon worker; a cancelled caller closes the connection, which frees it. */
    private suspend fun exchange(url: String): Int = suspendCancellableCoroutine { continuation ->
        val current = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { current.get()?.disconnect() }
        executor.execute {
            if (!continuation.isActive) return@execute
            inFlight.incrementAndGet()
            val result = try {
                runCatching {
                    HttpTransport.exchange(URL(url), null, REDIRECT_POLICY, timeoutMillis, maxBodyBytes = null, current) { continuation.isActive }.status
                }
            } finally {
                inFlight.decrementAndGet()
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }
}
