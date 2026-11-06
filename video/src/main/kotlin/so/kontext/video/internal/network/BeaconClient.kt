package so.kontext.video.internal.network

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * Each beacon has [timeoutMillis] in all, every redirect included, counted from when a worker starts it (a beacon
 * waiting its turn is never dropped for the wait): the socket timeouts are idle timeouts, and a tracker that
 * accepts the connection and then trickles its headers (or a chain of redirects, each with its own timeouts)
 * would hold a worker as long as it likes. Past the deadline the connection is closed, which ends the blocked
 * read, as `ApiClient` does. Beacons run on workers of their own, so a black-holed tracker cannot take the threads
 * the ads requests need, and at most [MAX_PER_HOST] of them go to one host at once, so one such tracker cannot
 * hold every worker while the other trackers' beacons of the moment wait.
 */
internal object BeaconClient {
    /** The whole exchange, every redirect included. A constant in spirit; tests shorten it. */
    @set:VisibleForTesting
    var timeoutMillis = 10_000

    /** At most this many beacons on the wire at once; the rest wait their turn. */
    private const val MAX_PARALLEL = 16

    /** At most this many beacons to one host (and port) at once: a host that never answers holds no more workers. */
    private const val MAX_PER_HOST = 4

    /**
     * Redirects as iOS's `URLSession` follows them for a beacon: up to `HttpTransport.MAX_REDIRECTS`, `http` to `https` and back,
     * as tracking chains do; the app's network security config still decides whether cleartext http may go.
     */
    private val REDIRECT_POLICY = HttpTransport.Redirects(allowsDowngrade = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val executor = Dispatchers.IO.limitedParallelism(MAX_PARALLEL).asExecutor()

    /** Exchanges running on a worker now, for tests: a beacon past its deadline leaves none behind. */
    val inFlight = AtomicInteger()

    /** The beacons waiting for or holding each host's turns; a host's entry goes with its last beacon. */
    private val lanes = HashMap<String, Lane>()

    private class Lane {
        val turns = Semaphore(MAX_PER_HOST)
        var users = 0
    }

    /** GETs [url]; [label] names it in the log (`ad f1a2b3c4: impression`). */
    fun send(url: String, label: String) {
        Environment.scope.launch {
            val status = runCatching { inLane(url) { exchange(url) } }
            status.onFailure { Logger.warning { "$label beacon failed, ${it.javaClass.simpleName}" } }
            val answer = status.getOrNull()
            when {
                status.isFailure -> Unit
                answer == null -> Logger.warning { "$label beacon gave up after ${timeoutMillis / 1000.0} s" }
                answer >= 400 -> Logger.warning { "$label beacon answered $answer" }
            }
        }
    }

    /** Runs [block] once [url]'s host has a turn free. A URL with no host to read gets a lane of its own. */
    private suspend fun <T> inLane(url: String, block: suspend () -> T): T {
        val host = runCatching { URL(url).authority }.getOrNull()?.lowercase() ?: url
        val lane = synchronized(lanes) { lanes.getOrPut(host) { Lane() }.also { it.users += 1 } }
        try {
            return lane.turns.withPermit { block() }
        } finally {
            synchronized(lanes) {
                lane.users -= 1
                if (lane.users == 0) lanes.remove(host)
            }
        }
    }

    /**
     * The status of [url]'s GET, run on a beacon worker; `null` when it took longer than [timeoutMillis] from the
     * moment the worker started it. A cancelled caller closes the connection, which frees the worker.
     */
    private suspend fun exchange(url: String): Int? = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val exchange = async { onWorker(url, started) }
        started.await()
        val status = withTimeoutOrNull(timeoutMillis.toLong()) { exchange.await() }
        if (status == null) exchange.cancel()
        status
    }

    private suspend fun onWorker(url: String, started: CompletableDeferred<Unit>): Int = suspendCancellableCoroutine { continuation ->
        val current = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { current.get()?.disconnect() }
        executor.execute {
            started.complete(Unit)
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
