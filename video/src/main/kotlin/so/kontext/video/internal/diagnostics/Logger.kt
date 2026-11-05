package so.kontext.video.internal.diagnostics

import android.util.Log
import so.kontext.video.KontextVideo.Configuration.LogLevel

/**
 * Writes the SDK's messages to Logcat (tag `KontextVideo`), up to the level set in `Configuration.logLevel`.
 * Callers pass ids, events, codes and URLs as they are, unredacted on purpose: the log is for development, read
 * with the development token's test ads, and the ad URLs are Kontext's own.
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
