package so.kontext.video.internal.network

import java.net.URI

/**
 * A URL's host, port (`-1`: none given) and user info, as the server URL check and the frame URL check read them;
 * each applies its own rules to them.
 */
internal class UrlAuthority(val host: String, val port: Int, val userInfo: String?) {
    companion object {
        private val registryHost = Regex("[A-Za-z0-9._-]+")

        /**
         * [uri]'s authority, or `null` when it has no host. `java.net.URI` gives no host for a name with `_`
         * (`http://ads_server:3002`, a compose service or LAN name), which the connection and the WebView reach
         * and Swift's `URL` accepts: read then from the raw authority. A port that is not a number reads as 0.
         */
        fun of(uri: URI): UrlAuthority? {
            if (!uri.host.isNullOrEmpty()) return UrlAuthority(uri.host, uri.port, uri.rawUserInfo)
            val raw = uri.rawAuthority
            if (raw.isNullOrEmpty()) return null
            val userInfo = if ('@' in raw) raw.substringBeforeLast('@') else null
            val hostAndPort = raw.substringAfterLast('@')
            val host = hostAndPort.substringBefore(':')
            if (!registryHost.matches(host)) return null
            val portText = hostAndPort.substringAfter(':', "")
            val port = when {
                portText.isEmpty() -> -1
                portText.all(Char::isDigit) -> portText.toIntOrNull() ?: 0
                else -> 0
            }
            return UrlAuthority(host, port, userInfo)
        }
    }
}
