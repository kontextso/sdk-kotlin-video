package so.kontext.video.internal.core

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.State
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.SDK_VERSION
import so.kontext.video.internal.content.PlayerOwnership
import so.kontext.video.internal.device.UserAgent
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.payloads.ConfigRequest
import so.kontext.video.internal.payloads.ConfigResponse
import so.kontext.video.internal.payloads.emptyFields
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.internal.state.SdkState

/**
 * The machinery behind `KontextVideo`, the shell around [SdkState]: it moves the SDK's state (`initialize`,
 * `/config` and its retry, the kill switch) and holds the values the app sets (`user`, `regulatory`, `variantId`)
 * and the one ad on screen in the whole app. Every public call on `KontextVideo` is a thin wrapper that forwards
 * here. Main thread only.
 *
 * Why not in `KontextVideo` itself: a JVM quirk. Kotlin's `internal` means "visible only inside this module", but
 * the JVM has no such level: an `internal` member of a public class compiles as public, with a slightly mangled
 * name, so a Java app could see and call it. Were the SDK's state (`configuration`, `initJob`,
 * `disable()`) internal members of the public `KontextVideo`, Java code could reach into it. An `internal object`
 * of its own keeps all of it out of reach. Swift needs no such split: its `internal` is truly invisible outside
 * the module, and the same state lives in `KontextVideo.swift`.
 */
internal object Sdk {
    /** The [State.Disabled] reason in a process other than the app's main one. */
    const val NOT_MAIN_PROCESS = "not the app's main process"

    /** The SDK's own facts, the one source of truth: its phase, the configuration, what `/config` said. */
    var state: SdkState = SdkState()
        private set
    val configuration: Configuration? get() = state.configuration
    var user: User? = null
        set(value) {
            field = value
            val empty = value?.emptyFields.orEmpty()
            if (empty.isEmpty()) return
            if ("id" in empty) Environment.assertionFailure("KontextVideo.user: id is empty")
            Logger.warning { "user: ${empty.joinToString()} empty, left out of the requests" }
        }
    var regulatory: Regulatory? = null
    var variantId: String? = null

    private var initJob: Deferred<Unit>? = null

    val isInitialized: Boolean get() = state.isInitialized

    /** The server's reason while the SDK is switched off, else `null`. */
    val disabledReason: String? get() = state.disabledReason

    fun initialize(context: Context, configuration: Configuration, onDone: ((State) -> Unit)?) {
        if (isInitialized) {
            Logger.warning { "initialize() was already called; this call is ignored" }
            return
        }
        val process = Environment.processName(context)
        val mainProcess = context.applicationInfo?.processName ?: context.packageName
        if (process != null && process != mainProcess) {
            // Another process of the app (a playback or download service): its own /config would count a
            // launch twice, and its WebView would take the data directory the main process's WebViews need.
            state.recordConfiguration(configuration)
            Logger.level = configuration.logLevel
            state.moveTo(State.Disabled(NOT_MAIN_PROCESS))
            Logger.warning { "initialize() in the process $process, not the app's main one; the SDK stays off here" }
            if (onDone != null) Environment.scope.launch { onDone(state.phase) }
            return
        }
        Environment.context = context.applicationContext
        state.recordConfiguration(configuration)
        Logger.level = configuration.logLevel
        val problems = configuration.problems
        if (problems.isNotEmpty()) {
            // A configuration mistake: every request would fail. Stops a debuggable app here, not at a 400 later.
            Environment.assertionFailure("KontextVideo.Configuration: ${problems.joinToString()}")
            Logger.error { "configuration: ${problems.joinToString()}; no ads will load" }
            if (configuration.publisherToken.isEmpty()) ErrorReporter.report(ErrorReport.Code.EMPTY_TOKEN, ErrorReport.Stage.INTEGRATION)
            if (problems.any { it.startsWith("adServerUrl") }) ErrorReporter.report(ErrorReport.Code.BAD_SERVER_URL, ErrorReport.Stage.INTEGRATION)
        }
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

    /**
     * Guards a call that needs `initialize` first. Calling the SDK before initializing it is a mistake in the
     * app's code: it stops a debuggable app; a release build only logs and carries on.
     */
    fun requireInitialized(call: String): Boolean {
        if (isInitialized) return true
        Environment.assertionFailure("KontextVideo: call initialize() before $call")
        Logger.error { "$call called before initialize(); no ads will load" }
        ErrorReporter.report(ErrorReport.Code.NOT_INITIALIZED, ErrorReport.Stage.INTEGRATION, detail = call)
        return false
    }

    /**
     * Guards a public call that must run on the main thread, as `@MainThread` says. Off it, the WebViews and
     * the SDK's state would be touched from two threads: a mistake in the app's code, so it stops a debuggable
     * app at the call; a release build logs it and carries on, as it does for the other integration mistakes.
     */
    fun checkMainThread(call: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        Environment.assertionFailure("KontextVideo: $call must be called on the main thread")
        Logger.error { "$call called on the thread ${Thread.currentThread().name}, not the main thread; call it on the main thread" }
    }

    /** `/config`'s `reportErrors`, recorded in [state]; [ErrorReporter] sends, keeps or drops its reports by it. */
    fun recordErrorReporting(isOn: Boolean) {
        state.recordErrorReporting(isOn)
        ErrorReporter.reportingChanged()
    }

    /** `/config`'s `reportDebug`, recorded in [state]; [DebugReporter] sends its events or drops them by it. */
    fun recordDebugReporting(isOn: Boolean) {
        state.recordDebugReporting(isOn)
        DebugReporter.reportingChanged()
    }

    /** After a failed `/config`, every ads request tries it again first. */
    private fun retryConfig() {
        val configuration = configuration ?: return
        if (!state.moveTo(State.Initializing)) return
        Logger.info { "Retrying /config" }
        initJob = Environment.scope.async { fetchConfig(configuration) }
    }

    /** One attempt, whatever the failure: after a failure the SDK is `Failed`, and the next ads request tries again. */
    private suspend fun fetchConfig(configuration: Configuration) {
        val context = Environment.context ?: return
        val request = ConfigRequest.make(context, configuration)
        val requestId = request.requestId
        try {
            when (val response = ConfigResponse.parse(ApiClient(configuration.adServerUrl).post("config", request.body))) {
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.moveTo(State.Failed)
            Logger.error { "/config failed, no ads until it succeeds: ${error.message}" }
            ErrorReporter.report(ErrorReport.Code.CONFIG_FAILED, ErrorReport.Stage.CONFIG, requestId = requestId, failure = ErrorReport.FailedRequest(error))
        }
    }

    /** A configuration without `/config`, as if initialized and enabled. Tests only. */
    fun configureForTesting(configuration: Configuration) {
        state.enableForTesting(configuration)
    }

    /** Back to the state before `initialize`. Tests only: the SDK is process-wide. */
    fun resetForTesting() {
        initJob?.cancel()
        initJob = null
        state = SdkState()
        user = null
        regulatory = null
        variantId = null
        ScreenSlot.resetForTesting()
        DebugReporter.resetForTesting()
        ErrorReporter.resetForTesting()
        UserAgent.resetForTesting()
        PlayerOwnership.resetForTesting()
        Logger.level = Configuration.LogLevel.WARNING
    }
}

/** An absolute http(s) URL with a host. */
private val AD_SERVER_URL = Regex("^https?://[^/?#\\s]+.*$")

/** What is wrong with the options, one phrase each; empty when they are usable. Checked by `initialize`. */
internal val Configuration.problems: List<String>
    get() = listOfNotNull(
        if (publisherToken.isEmpty()) "publisherToken is empty" else null,
        if (!AD_SERVER_URL.matches(adServerUrl)) "adServerUrl $adServerUrl is not absolute" else null,
    )
