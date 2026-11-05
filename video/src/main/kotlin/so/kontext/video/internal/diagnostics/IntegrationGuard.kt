package so.kontext.video.internal.diagnostics

import android.os.Looper
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.payloads.emptyFields
import so.kontext.video.internal.payloads.leftOut
import so.kontext.video.internal.payloads.missingRequired
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState

/**
 * The checks of the app's own mistakes in using the SDK: a call off the main thread or before `initialize`, a
 * configuration, content or user that cannot work. Each stops a debuggable app at the mistake (an assertion),
 * logs it, and reports it where it is reported; a release build carries on.
 */
internal object IntegrationGuard {
    /**
     * Guards a public call that must run on the main thread, as `@MainThread` says. Off it, the WebViews and
     * the SDK's state would be touched from two threads: a mistake in the app's code, so it stops a debuggable
     * app at the call; a release build logs it and carries on, as it does for the other integration mistakes.
     */
    fun requireMainThread(call: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        Environment.assertionFailure("KontextVideo: $call must be called on the main thread")
        Logger.error { "$call called on the thread ${Thread.currentThread().name}, not the main thread; call it on the main thread" }
    }

    /**
     * Guards a call that needs `initialize` first. Calling the SDK before initializing it is a mistake in the
     * app's code: it stops a debuggable app; a release build only logs and carries on.
     */
    fun requireInitialized(call: String): Boolean {
        if (SdkState.current.isInitialized) return true
        Environment.assertionFailure("KontextVideo: call initialize() before $call")
        Logger.error { "$call called before initialize(); no ads will load" }
        ErrorReporter.report(ErrorReport.Code.NOT_INITIALIZED, ErrorReport.Stage.INTEGRATION, detail = call)
        return false
    }

    /** A configuration mistake: every request would fail. Stops a debuggable app here, not at a 400 later. */
    fun checkConfiguration(configuration: Configuration) {
        val problems = configuration.problems
        if (problems.isEmpty()) return
        Environment.assertionFailure("KontextVideo.Configuration: ${problems.joinToString()}")
        Logger.error { "configuration: ${problems.joinToString()}; no ads will load" }
        if (configuration.publisherToken.isEmpty()) ErrorReporter.report(ErrorReport.Code.EMPTY_TOKEN, ErrorReport.Stage.INTEGRATION)
        if (problems.any { it.startsWith("adServerUrl") }) ErrorReporter.report(ErrorReport.Code.BAD_SERVER_URL, ErrorReport.Stage.INTEGRATION)
    }

    /** What the publisher passed wrong in [content], said once when its `VideoAds` is made rather than as a server 400 later. */
    fun checkContent(content: Content, sessionId: String) {
        val missing = content.missingRequired
        if (missing.isNotEmpty()) {
            Environment.assertionFailure("Content: ${missing.joinToString()}")
            Logger.error { "content ${content.id}: ${missing.joinToString()}; the server will refuse its requests" }
            ErrorReporter.report(ErrorReport.Code.BAD_CONTENT, ErrorReport.Stage.INTEGRATION, sessionId = sessionId, detail = missing.joinToString(","))
        }
        if (content.leftOut.isNotEmpty()) Logger.warning { "content ${content.id}: ${content.leftOut.joinToString()}" }
    }

    /** A user with empty fields: they are left out of the requests; an empty id stops a debuggable app. */
    fun checkUser(user: User?) {
        val empty = user?.emptyFields.orEmpty()
        if (empty.isEmpty()) return
        if ("id" in empty) Environment.assertionFailure("KontextVideo.user: id is empty")
        Logger.warning { "user: ${empty.joinToString()} empty, left out of the requests" }
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
