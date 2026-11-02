package so.kontext.video.internal.support

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
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

    /** Whether an Activity of the app is started (or on screen at `initialize`): the clocks that count only time in front read it. */
    var isAppInForeground: () -> Boolean = { ActivityTracker.isAppInForeground }

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

    /** How long an ads request waits for [userAgent] before it goes with a fallback. A constant in spirit; tests shorten it. */
    var userAgentWaitMillis = DEFAULT_USER_AGENT_WAIT_MILLIS
    const val DEFAULT_USER_AGENT_WAIT_MILLIS = 2_000L

    /** Sends one beacon; tests record them instead. */
    var sendBeacon: (url: String, label: String) -> Unit = Tracker::get

    /** The advertising id when the viewer allows it, else `null`. */
    var advertisingId: suspend () -> String? = { context?.let { DeviceFacts.advertisingId(it) } }

    /** The name of the process the SDK runs in, `null` when it cannot be read. */
    var processName: (Context) -> String? = ::currentProcessName

    fun reset() {
        processName = ::currentProcessName
        scope = newScope()
        now = { SystemClock.elapsedRealtime() }
        assertionFailure = ::failInDebuggableApp
        userAgent = { context?.let { DeviceFacts.userAgent(it) } }
        userAgentWaitMillis = DEFAULT_USER_AGENT_WAIT_MILLIS
        advertisingId = { context?.let { DeviceFacts.advertisingId(it) } }
        isAppInForeground = { ActivityTracker.isAppInForeground }
        sendBeacon = Tracker::get
    }

    private fun failInDebuggableApp(message: String) {
        val debuggable = context?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: false
        check(!debuggable) { message }
    }

    private fun currentProcessName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return@runCatching Application.getProcessName()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }.getOrNull()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
