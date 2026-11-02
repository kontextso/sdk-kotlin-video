package so.kontext.video.internal

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.State
import so.kontext.video.KontextVideo.User
import so.kontext.video.VideoAd
import so.kontext.video.internal.ad.Presentation
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.ConfigResponse
import java.util.UUID

/** What `KontextVideo` holds: the configuration, where `/config` got to, and the one ad on screen. Main thread only. */
internal object Sdk {
    var configuration: Configuration? = null
        private set
    var state: State = State.NotInitialized
        private set
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

    /**
     * The one ad on screen in the whole app, from any `VideoAds`: taken at `show`, released at the end; it
     * retains the ad until then, whatever the app does with its reference.
     */
    var presentation: Presentation? = null
    val adOnScreen: VideoAd? get() = presentation?.ad

    private var initJob: Deferred<Unit>? = null

    val isInitialized: Boolean get() = configuration != null

    /** The server's reason while the SDK is switched off, else `null`. */
    val disabledReason: String? get() = (state as? State.Disabled)?.reason

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
            this.configuration = configuration
            Logger.level = configuration.logLevel
            state = State.Disabled(NOT_MAIN_PROCESS)
            Logger.warning { "initialize() in the process $process, not the app's main one; the SDK stays off here" }
            if (onDone != null) Environment.scope.launch { onDone(state) }
            return
        }
        Environment.context = context.applicationContext
        this.configuration = configuration
        Logger.level = configuration.logLevel
        val problems = configuration.problems
        if (problems.isNotEmpty()) {
            // A configuration mistake: every request would fail. Stops a debuggable app here, not at a 400 later.
            Environment.assertionFailure("KontextVideo.Configuration: ${problems.joinToString()}")
            Logger.error { "configuration: ${problems.joinToString()}; no ads will load" }
            if (configuration.publisherToken.isEmpty()) ErrorReporter.report(ErrorReporter.Code.EMPTY_TOKEN, ErrorReporter.Stage.INTEGRATION)
            if (problems.any { it.startsWith("adServerUrl") }) ErrorReporter.report(ErrorReporter.Code.BAD_SERVER_URL, ErrorReporter.Stage.INTEGRATION)
        }
        Logger.debug { "Initializing, SDK version ${SDK_VERSION}" }
        state = State.Initializing
        val job = Environment.scope.async { fetchConfig(configuration) }
        initJob = job
        if (onDone != null) {
            Environment.scope.launch {
                job.await()
                onDone(state)
            }
        }
    }

    suspend fun awaitInitialization(): State {
        initJob?.await()
        return state
    }

    /**
     * Whether ads may be requested now: waits while `/config` is in flight, retries it first after a failure,
     * and answers `false` when the SDK is off. A cancelled caller stops waiting at once; `/config` goes on.
     */
    suspend fun waitUntilEnabled(): Boolean {
        if (state == State.Failed) retryConfig()
        if (state == State.Initializing) initJob?.await()
        return state == State.Enabled
    }

    /** An ads response said `enabled: false`: the same kill switch as `/config`. */
    fun disable(reason: String) {
        if (state == State.Disabled(reason)) return
        state = State.Disabled(reason)
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
        ErrorReporter.report(ErrorReporter.Code.NOT_INITIALIZED, ErrorReporter.Stage.INTEGRATION, detail = call)
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

    /** After a failed `/config`, every ads request tries it again first. */
    private fun retryConfig() {
        val configuration = configuration ?: return
        if (state != State.Failed) return
        Logger.info { "Retrying /config" }
        state = State.Initializing
        initJob = Environment.scope.async { fetchConfig(configuration) }
    }

    /** One attempt, whatever the failure: after a failure the SDK is `Failed`, and the next ads request tries again. */
    private suspend fun fetchConfig(configuration: Configuration) {
        val context = Environment.context ?: return
        val requestId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("requestId", requestId)
            put("publisherToken", configuration.publisherToken)
            put("installId", DeviceFacts.installId(context))
            put("sdk", DeviceFacts.sdk())
            put("app", DeviceFacts.app(context))
            put(
                "device",
                buildJsonObject {
                    put("os", DeviceFacts.os(context))
                    put("hardware", DeviceFacts.hardware(context))
                },
            )
        }
        try {
            when (val response = ConfigResponse.parse(ApiClient(configuration.adServerUrl).post("config", body))) {
                is ConfigResponse.Enabled -> {
                    DebugReporter.isEnabled = response.reportDebug
                    ErrorReporter.configure(response.reportErrors)
                    DebugReporter.event("config.response", state = mapOf("enabled" to true))
                    state = State.Enabled
                    Logger.info { "Enabled" }
                    // The browser user agent every ads request carries, read in a WebView once per process:
                    // started now, so the first request rarely waits for it.
                    Environment.scope.launch { Environment.userAgent() }
                }
                is ConfigResponse.Disabled -> {
                    state = State.Disabled(response.reason)
                    ErrorReporter.configure(false)
                    Logger.warning { "Disabled by the server: ${response.reason}" }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = State.Failed
            Logger.error { "/config failed, no ads until it succeeds: ${error.message}" }
            ErrorReporter.report(ErrorReporter.Code.CONFIG_FAILED, ErrorReporter.Stage.CONFIG, requestId = requestId, failure = ErrorReporter.RequestFailure(error))
        }
    }

    /** The [State.Disabled] reason in a process other than the app's main one. */
    const val NOT_MAIN_PROCESS = "not the app's main process"

    /** A configuration without `/config`, as if initialized and enabled. Tests only. */
    fun configureForTesting(configuration: Configuration) {
        this.configuration = configuration
        state = State.Enabled
    }

    /** Back to the state before `initialize`. Tests only: the SDK is process-wide. */
    fun resetForTesting() {
        initJob?.cancel()
        initJob = null
        configuration = null
        state = State.NotInitialized
        user = null
        regulatory = null
        variantId = null
        presentation = null
        DebugReporter.resetForTesting()
        ErrorReporter.resetForTesting()
        DeviceFacts.resetForTesting()
        Logger.level = Configuration.LogLevel.WARNING
    }
}
