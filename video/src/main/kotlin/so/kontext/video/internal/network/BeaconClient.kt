package so.kontext.video.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger
import java.net.URL

/**
 * Sends beacons: a GET of a tracking URL, off the main thread, once, with no retries, fire and forget. Only the
 * status is read, never the body. Which beacons fire and when is `Tracker`'s; this only delivers a finished URL.
 */
internal object BeaconClient {
    private const val TIMEOUT_MILLIS = 10_000

    /**
     * Redirects as iOS's `URLSession` follows them for a beacon: up to `HttpTransport.MAX_REDIRECTS`, `http` to `https` and back,
     * as tracking chains do; the app's network security config still decides whether cleartext http may go.
     */
    private val REDIRECT_POLICY = HttpTransport.Redirects(allowDowngrade = true)

    /** GETs [url]; [label] names it in the log (`ad f1a2b3c4: impression`). */
    fun send(url: String, label: String) {
        Environment.scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val answer = HttpTransport.exchange(URL(url), null, REDIRECT_POLICY, TIMEOUT_MILLIS, maxBodyBytes = null)
                    if (answer.status >= 400) Logger.warning { "$label beacon answered ${answer.status}" }
                }.onFailure { Logger.warning { "$label beacon failed, ${it.javaClass.simpleName}" } }
            }
        }
    }
}
