package so.kontext.video.internal.diagnostics

import android.util.Log
import so.kontext.video.KontextVideo.Configuration.LogLevel

/**
 * Writes the SDK's messages to Logcat (tag `KontextVideo`), up to the level set in `Configuration.logLevel`, and,
 * while `/config` asks for the debug log, hands every message of every level to [DebugReporter] as the event `log`.
 * Callers pass ids, events, codes and URLs as they are, unredacted on purpose: the log is for development, read
 * with the development token's test ads, and the ad URLs are Kontext's own. A message is built only when Logcat
 * or the reporter takes it.
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

    /** A line for Logcat only, never forwarded: [DebugReporter]'s own lines, which would otherwise make events of themselves. */
    fun console(messageLevel: LogLevel, message: () -> String) = log(messageLevel, message, forward = false)

    private fun log(messageLevel: LogLevel, build: () -> String, forward: Boolean = true) {
        if (messageLevel == LogLevel.NONE) return
        val forwards = forward && DebugReporter.forwardsConsole
        val shows = messageLevel <= level
        if (!shows && !forwards) return
        val message = build()
        if (forwards) DebugReporter.forward(messageLevel, message)
        if (!shows) return
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
