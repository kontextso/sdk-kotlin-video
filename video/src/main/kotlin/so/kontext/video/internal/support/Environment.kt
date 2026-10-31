package so.kontext.video.internal.support

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.rendering.Tracker

/**
 * The SDK's seams: what tests replace and production never touches. Every value has its production
 * default; [reset] restores them all.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only (see `context`)
internal object Environment {
    /** The application context, set by `KontextVideo.initialize`; never an Activity's, so nothing leaks. */
    @SuppressLint("StaticFieldLeak") // the application context lives as long as the process
    var context: Context? = null
        set(value) {
            field = value?.applicationContext
            (field as? Application)?.let(ActivityTracker::register)
        }

    /** Whether an Activity of the app is started: the clocks that count only time in front read it. */
    var isAppInForeground: () -> Boolean = { ActivityTracker.startedCount > 0 }

    /** Where the SDK's own work runs: the main thread, never blocking it. */
    var scope: CoroutineScope = newScope()

    /**
     * The clock an ad's lifetime is measured on, in milliseconds: monotonic and still counting while the
     * device sleeps (an ad must expire in a locked pocket too). Never wall time: the user can set that one.
     */
    var now: () -> Long = { SystemClock.elapsedRealtime() }

    /** A programmer error: stops a debuggable app, only logs in a release build. Tests record it instead. */
    var assertionFailure: (String) -> Unit = ::failInDebuggableApp

    /** The browser's user agent, `navigator.userAgent` of a WebView, as KontextKit reads and caches it. */
    var userAgent: suspend () -> String? = { context?.let { DeviceFacts.userAgent(it) } }

    /** Sends one beacon; tests record them instead. */
    var sendBeacon: (url: String, label: String) -> Unit = Tracker::get

    /** The advertising id when the viewer allows it, else `null`. */
    var advertisingId: suspend () -> String? = { context?.let { DeviceFacts.advertisingId(it) } }

    fun reset() {
        scope = newScope()
        now = { SystemClock.elapsedRealtime() }
        assertionFailure = ::failInDebuggableApp
        userAgent = { context?.let { DeviceFacts.userAgent(it) } }
        advertisingId = { context?.let { DeviceFacts.advertisingId(it) } }
        isAppInForeground = { ActivityTracker.startedCount > 0 }
        sendBeacon = Tracker::get
    }

    private fun failInDebuggableApp(message: String) {
        val debuggable = context?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: false
        check(!debuggable) { message }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
