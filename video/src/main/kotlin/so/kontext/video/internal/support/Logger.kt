package so.kontext.video.internal.support

import android.util.Log
import so.kontext.video.KontextVideo.Configuration.LogLevel

/**
 * Writes the SDK's messages to Logcat (tag `KontextVideo`), up to the level set in `Configuration.logLevel`.
 * Callers pass ids, events and codes, never anything private; a URL from outside goes through [describe].
 * A message is built only when its level is on.
 */
internal object Logger {
    const val TAG = "KontextVideo"

    @Volatile var level: LogLevel = LogLevel.WARNING

    /** Tests only: receives every message that passes the level check. */
    @Volatile var sink: ((LogLevel, String) -> Unit)? = null

    fun error(message: () -> String) = log(LogLevel.ERROR, message)
    fun warning(message: () -> String) = log(LogLevel.WARNING, message)
    fun info(message: () -> String) = log(LogLevel.INFO, message)
    fun debug(message: () -> String) = log(LogLevel.DEBUG, message)

    /** A URL from outside as the log may show it: scheme and host, never the path, query or anything else. */
    fun describe(url: String): String {
        val match = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(//([^/?#]*))?").find(url) ?: return "(no scheme)"
        val scheme = match.groupValues[1] + ":"
        // The host alone: the user info and the port go, as they do in the Swift SDK's log.
        val host = match.groups[3]?.value?.substringAfterLast('@')?.replace(Regex(":\\d*$"), "")
        return if (host.isNullOrEmpty()) scheme else "$scheme//$host"
    }

    private fun log(messageLevel: LogLevel, build: () -> String) {
        if (messageLevel == LogLevel.NONE || messageLevel > level) return
        val message = build()
        sink?.invoke(messageLevel, message)
        runCatching {
            when (messageLevel) {
                LogLevel.ERROR -> Log.e(TAG, message)
                LogLevel.WARNING -> Log.w(TAG, message)
                LogLevel.INFO -> Log.i(TAG, message)
                LogLevel.DEBUG -> Log.d(TAG, message)
                LogLevel.NONE -> Unit
            }
        }
    }
}
