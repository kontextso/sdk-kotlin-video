package so.kontext.video.internal

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.State
import so.kontext.video.internal.content.PlayerOwnership
import so.kontext.video.internal.device.UserAgent
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.IntegrationGuard
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.network.ConfigFetcher
import so.kontext.video.internal.payloads.ConfigResponse
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.internal.state.SdkState

/**
 * Initializes the SDK and keeps it on or off: `initialize` and `/config`, its retry before an ads request after a
 * failure, the kill switch of an ads answer, and the report switches `/config` sets. It moves [SdkState.current];
 * every public call on `KontextVideo` that starts the SDK forwards here. Main thread only. The entry point of
 * the internals; everything else lives in a folder named by its topic.
 *
 * Why not in `KontextVideo` itself: a JVM quirk. Kotlin's `internal` means "visible only inside this module", but
 * the JVM has no such level: an `internal` member of a public class compiles as public, with a slightly mangled
 * name, so a Java app could see and call it. Were the SDK's machinery (`initJob`, `disable()`) internal members of
 * the public `KontextVideo`, Java code could reach into it. An `internal object` of its own keeps all of it out
 * of reach. Swift needs no such split: its `internal` is truly invisible outside the module, and the same state
 * lives in `KontextVideo.swift`.
 */
internal object SdkInitializer {
    /** The [State.Disabled] reason in a process other than the app's main one. */
    const val NOT_MAIN_PROCESS = "not the app's main process"

    private val state: SdkState get() = SdkState.current

    private var initJob: Deferred<Unit>? = null

    fun initialize(context: Context, configuration: Configuration, onDone: ((State) -> Unit)?) {
        if (state.isInitialized) {
            Logger.warning { "initialize() was already called; this call is ignored" }
            return
        }
        // 1. Another process of the app (a playback or download service): its own /config would count a launch
        //    twice, and its WebView would take the data directory the main process's WebViews need. Off there.
        val process = Environment.processName(context)
        val mainProcess = context.applicationInfo?.processName ?: context.packageName
        if (process != null && process != mainProcess) {
            state.recordConfiguration(configuration)
            Logger.level = configuration.logLevel
            state.moveTo(State.Disabled(NOT_MAIN_PROCESS))
            Logger.warning { "initialize() in the process $process, not the app's main one; the SDK stays off here" }
            if (onDone != null) Environment.scope.launch { onDone(state.phase) }
            return
        }
        // 2. The configuration recorded, the log level set, its mistakes said.
        Environment.context = context.applicationContext
        state.recordConfiguration(configuration)
        Logger.level = configuration.logLevel
        IntegrationGuard.checkConfiguration(configuration)
        // 3. `/config` starts; the listener hears its outcome.
        Logger.debug { "Initializing, SDK version ${SDK_VERSION}" }
        state.moveTo(State.Initializing)
        val job = Environment.scope.async { fetchConfig(configuration) }
        initJob = job
        if (onDone != null) {
            Environment.scope.launch {
                job.await()
                onDone(state.phase)
            }
        }
    }

    suspend fun awaitInitialization(): State {
        initJob?.await()
        return state.phase
    }

    /**
     * Whether ads may be requested now: waits while `/config` is in flight, retries it first after a failure,
     * and answers `false` when the SDK is off. A cancelled caller stops waiting at once; `/config` goes on.
     */
    suspend fun waitUntilEnabled(): Boolean {
        if (state.phase == State.Failed) retryConfig()
        if (state.phase == State.Initializing) initJob?.await()
        return state.phase == State.Enabled
    }

    /** An ads response said `enabled: false`: the same kill switch as `/config`. */
    fun disable(reason: String) {
        if (!state.moveTo(State.Disabled(reason))) return
        Logger.warning { "Disabled by the server: $reason" }
    }

    /** `/config`'s `reportErrors`, recorded in the state; [ErrorReporter] sends, keeps or drops its reports by it. */
    fun recordErrorReporting(isOn: Boolean) {
        state.recordErrorReporting(isOn)
        ErrorReporter.reportingChanged()
    }

    /** `/config`'s `reportDebug`, recorded in the state; [DebugReporter] sends its events or drops them by it. */
    fun recordDebugReporting(isOn: Boolean) {
        state.recordDebugReporting(isOn)
        DebugReporter.reportingChanged()
    }

    /** A configuration without `/config`, as if initialized and enabled. Tests only. */
    fun configureForTesting(configuration: Configuration) {
        state.enableForTesting(configuration)
    }

    /** Back to the state before `initialize`. Tests only: the SDK is process-wide. */
    fun resetForTesting() {
        initJob?.cancel()
        initJob = null
        SdkState.resetForTesting()
        ScreenSlot.resetForTesting()
        DebugReporter.resetForTesting()
        ErrorReporter.resetForTesting()
        UserAgent.resetForTesting()
        PlayerOwnership.resetForTesting()
        Logger.level = Configuration.LogLevel.WARNING
    }

    /** After a failed `/config`, every ads request tries it again first. */
    private fun retryConfig() {
        val configuration = state.configuration ?: return
        if (!state.moveTo(State.Initializing)) return
        Logger.info { "Retrying /config" }
        initJob = Environment.scope.async { fetchConfig(configuration) }
    }

    /** One attempt, whatever the failure: after a failure the SDK is `Failed`, and the next ads request tries again. */
    private suspend fun fetchConfig(configuration: Configuration) {
        val context = Environment.context ?: return
        when (val response = ConfigFetcher.fetch(context, configuration) { state.moveTo(State.Failed) }) {
            is ConfigResponse.Enabled -> {
                recordDebugReporting(response.reportDebug)
                recordErrorReporting(response.reportErrors)
                DebugReporter.event("config.response", state = mapOf("enabled" to true))
                state.moveTo(State.Enabled)
                Logger.info { "Enabled" }
                // The browser user agent every ads request carries, read in a WebView once per process:
                // started now, so the first request rarely waits for it.
                Environment.scope.launch { Environment.userAgent() }
            }
            is ConfigResponse.Disabled -> {
                state.moveTo(State.Disabled(response.reason))
                recordErrorReporting(false)
                Logger.warning { "Disabled by the server: ${response.reason}" }
            }
            null -> Unit
        }
    }
}
