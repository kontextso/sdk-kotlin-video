package so.kontext.video.internal.frame

import android.net.Uri

/**
 * A web origin: a scheme, host and port, the default port spelled out. The page's own origin is the one sender
 * whose messages count and the one a navigation may stay in; [rule] is how the WebView's message listener names it.
 */
internal data class Origin(val scheme: String, val host: String, val port: Int) {
    val rule: String get() = if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"

    companion object {
        fun of(url: String): Origin? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port == -1) defaultPort(scheme) else uri.port
            return Origin(scheme, host, port)
        }

        private fun defaultPort(scheme: String) = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }
}
