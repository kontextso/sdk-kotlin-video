package so.kontext.video.internal.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.device.UserAgent
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.network.BeaconClient

/**
 * The SDK's seams. [context] is set once, by `KontextVideo.initialize` (its setter also registers
 * [AppActivities]); every other value is a production default that only tests replace, and [reset] restores
 * them all from the same defaults.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only (see `context`)
internal object Environment {
    /** How long an ads request waits for [userAgent] before it goes with a fallback. A constant in spirit; tests shorten it. */
    const val DEFAULT_USER_AGENT_WAIT_MILLIS = 2_000L

    /** The application context, set by `KontextVideo.initialize`; never an Activity's, so nothing leaks. */
    @SuppressLint("StaticFieldLeak") // the application context lives as long as the process
    var context: Context? = null
        set(value) {
            field = value?.applicationContext
            (field as? Application)?.let(AppActivities::register)
        }

    /** Whether an Activity of the app is started (or on screen at `initialize`): the clocks that count only time in front read it. */
    @set:VisibleForTesting
    var isAppInForeground: () -> Boolean = defaultIsAppInForeground

    /** When the app last came in front (an Activity started with none before), on [now]'s clock; `Long.MIN_VALUE` before any. */
    @set:VisibleForTesting
    var cameToFrontAt: () -> Long = defaultCameToFrontAt

    /** Where the SDK's own work runs: the main thread, never blocking it. */
    @set:VisibleForTesting
    var scope: CoroutineScope = newScope()

    /**
     * The clock an ad's lifetime is measured on, in milliseconds: monotonic and still counting while the
     * device sleeps (an ad must expire in a locked pocket too). Never wall time: the user can set that one.
     */
    @set:VisibleForTesting
    var now: () -> Long = defaultNow

    /** A programmer error: stops a debuggable app, only logs in a release build. Tests record it instead. */
    @set:VisibleForTesting
    var assertionFailure: (String) -> Unit = defaultAssertionFailure

    /** The browser's user agent, `navigator.userAgent` of a WebView, as KontextKit reads and caches it. */
    @set:VisibleForTesting
    var userAgent: suspend () -> String? = defaultUserAgent

    /** How long an ads request waits for [userAgent]; [DEFAULT_USER_AGENT_WAIT_MILLIS] but in tests. */
    @set:VisibleForTesting
    var userAgentWaitMillis = DEFAULT_USER_AGENT_WAIT_MILLIS

    /** Sends one beacon; its job ends with the beacon's outcome (`null`: nothing to wait for). Tests record them instead. */
    @set:VisibleForTesting
    var sendBeacon: (url: String, label: String) -> Job? = defaultSendBeacon

    /** The advertising id when the viewer allows it, else `null`. */
    @set:VisibleForTesting
    var advertisingId: suspend () -> String? = defaultAdvertisingId

    /** The name of the process the SDK runs in, `null` when it cannot be read. */
    @set:VisibleForTesting
    var processName: (Context) -> String? = defaultProcessName

    /** Makes an ad's bridge; tests make one that throws, as a device without a working WebView does. */
    @set:VisibleForTesting
    var makeBridge: (Context, String, String, Boolean, Long?) -> FrameBridge = defaultMakeBridge

    // The production defaults, each written once: the initializers above and reset() use them.
    private val defaultIsAppInForeground: () -> Boolean get() = { AppActivities.isAppInForeground }
    private val defaultCameToFrontAt: () -> Long get() = { AppActivities.cameToFrontAt }
    private val defaultNow: () -> Long get() = { SystemClock.elapsedRealtime() }
    private val defaultAssertionFailure: (String) -> Unit get() = ::failInDebuggableApp
    private val defaultUserAgent: suspend () -> String? get() = { context?.let { UserAgent.userAgent(it) } }
    private val defaultSendBeacon: (String, String) -> Job? get() = BeaconClient::send
    private val defaultAdvertisingId: suspend () -> String? get() = { context?.let { DeviceInfo.advertisingId(it) } }
    private val defaultProcessName: (Context) -> String? get() = ::currentProcessName
    private val defaultMakeBridge: (Context, String, String, Boolean, Long?) -> FrameBridge get() = ::FrameBridge

    fun reset() {
        processName = defaultProcessName
        // The last test's work ends with it: a request still out cannot change the next test's SDK.
        scope.cancel()
        scope = newScope()
        now = defaultNow
        assertionFailure = defaultAssertionFailure
        userAgent = defaultUserAgent
        userAgentWaitMillis = DEFAULT_USER_AGENT_WAIT_MILLIS
        advertisingId = defaultAdvertisingId
        isAppInForeground = defaultIsAppInForeground
        cameToFrontAt = defaultCameToFrontAt
        sendBeacon = defaultSendBeacon
        makeBridge = defaultMakeBridge
    }

    private fun failInDebuggableApp(message: String) {
        val isDebuggable = context?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: false
        check(!isDebuggable) { message }
    }

    private fun currentProcessName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return@runCatching Application.getProcessName()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }.getOrNull()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
