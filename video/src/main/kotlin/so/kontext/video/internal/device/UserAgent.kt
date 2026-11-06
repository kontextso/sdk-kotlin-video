package so.kontext.video.internal.device

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import so.kontext.kit.deviceinfo.NetworkInfoProvider
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.platform.Environment

/**
 * The user agent an ads request carries: the WebView's default one (`WebSettings.getDefaultUserAgent`, the
 * `navigator.userAgent` of a WebView left as it is), read once through KontextKit on a worker and shared by every
 * caller, waited for at most [Environment.userAgentWaitMillis], else a fallback of the same shape.
 */
internal object UserAgent {
    private const val TABLET_WIDTH_DP = 600

    /** The one user-agent lookup in flight or done: KontextKit makes a WebView until it has cached the answer. */
    private var userAgentLookup: Deferred<String?>? = null

    /**
     * The blocking read: KontextKit calls `WebSettings.getDefaultUserAgent`, which loads and starts the WebView
     * (hundreds of ms, over a second on a cold low-end device). Its documentation sets no thread rule, so it runs
     * on a worker: the caller's main thread stays free, and the request's wait ([requestUserAgent]) can give up on
     * time. Tests replace it.
     */
    @set:VisibleForTesting
    var read: (Context) -> String? = { NetworkInfoProvider.collect(it).userAgent }

    /** The WebView's default user agent; `null` when WebKit could not answer, and the next caller tries again. */
    @Suppress("MemberNameEqualsClassName") // the name its callers had on DeviceInfo, and Swift's
    suspend fun userAgent(context: Context): String? {
        val lookup = userAgentLookup ?: Environment.scope.lookUpUserAgent(context).also { userAgentLookup = it }
        val agent = lookup.await()
        if (agent == null && userAgentLookup === lookup) userAgentLookup = null
        return agent
    }

    private fun CoroutineScope.lookUpUserAgent(context: Context): Deferred<String?> =
        async(Dispatchers.IO) { runCatching { read(context) }.getOrNull() }

    /**
     * The user agent an ads request carries: the WebView's ([Environment.userAgent]), waited for at most
     * [Environment.userAgentWaitMillis]. On a cold device the WebView's first answer can take many seconds, and
     * the request's own timeout starts only once it is built: past the wait, the request goes with
     * [fallbackUserAgent] and the lookup goes on for the requests after it (waiting gives up, it does not
     * cancel the shared read). A lookup that found none in time sends none, as before; a cancelled caller (its
     * `VideoAds` destroyed) stops at once. As the Swift SDK does.
     */
    suspend fun requestUserAgent(context: Context): String? {
        val wait = Environment.userAgentWaitMillis
        val found = withTimeoutOrNull(wait) { Found(Environment.userAgent()) }
        if (found != null) return found.agent
        Logger.debug { "user agent not ready within $wait ms; this request carries the fallback" }
        return fallbackUserAgent(context)
    }

    private class Found(val agent: String?)

    /**
     * The shape of an Android WebView's user agent, from the OS version, the device and the installed WebView,
     * for a request that cannot wait for the real one. A tablet's has no `Mobile`, as the WebView's has not.
     */
    fun fallbackUserAgent(context: Context): String {
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        val chrome = webView?.substringBefore(' ')?.takeIf { it.isNotEmpty() }?.let { " Chrome/$it" } ?: ""
        val mobile = if (context.resources.configuration.smallestScreenWidthDp >= TABLET_WIDTH_DP) "" else " Mobile"
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID}; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0$chrome$mobile Safari/537.36"
    }

    fun resetForTesting() {
        userAgentLookup = null
        read = { NetworkInfoProvider.collect(it).userAgent }
    }
}
