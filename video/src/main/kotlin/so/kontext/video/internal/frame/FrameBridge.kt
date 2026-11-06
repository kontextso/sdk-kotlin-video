package so.kontext.video.internal.frame

import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.platform.LinkOpener

/**
 * Owns the WebView of one ad and speaks the Kontext Frame Protocol v2 with its page: the imperative shell around
 * [FrameProtocol], which decides what each message means. The page posts to `window.KontextAndroid.postMessage(json)`,
 * injected by `addWebMessageListener` for the page's own origin only; the bridge answers with `window.postMessage`
 * into the page. One bridge, one ad.
 *
 * ```
 *   page message ──► receive ──► FrameProtocol.accept ──► Step ──► execute
 *                                                                   ignore: nothing
 *                                                                   drop: a log line, a violation reported once
 *                                                                   loaded: the load timeout stops, the page pauses
 *                                                                   deliver: onEvent
 *                                                                   endThenDeliver: the page torn down, then onEvent
 * ```
 *
 * Here: the WebView ([FrameWebView], its lockdown) and its client ([FrameWebViewClient], what it reports of the
 * page's loads), the load and its timeout, pausing and resuming the page, what the SDK sends the page, and the
 * teardown.
 */
internal class FrameBridge(
    /** The application's context: the WebView's base while the ad is not on screen. */
    private val applicationContext: Context,
    val adId: String,
    private val url: String,
    isVideo: Boolean,
    /** The video's length when the server gives one (a linear ad); without it the playhead is capped at a day. */
    videoDurationMillis: Long? = null,
) {
    /** Why the bridge failed: several causes fire the same VAST code, and the error report keeps them apart ([reportCode]). */
    enum class FailureCause(val reportCode: ErrorReport.Code) {
        PAGE(ErrorReport.Code.CREATIVE_FAILED),
        LOAD_TIMEOUT(ErrorReport.Code.LOAD_TIMEOUT),
        LOAD_FAILED(ErrorReport.Code.FRAME_LOAD_FAILED),
        PROCESS_TERMINATED(ErrorReport.Code.PROCESS_TERMINATED),
        UNRESPONSIVE(ErrorReport.Code.UNRESPONSIVE),
    }

    /**
     * The WebView's context: the application's while the page loads off screen and after the ad ends, the
     * showing Activity's while it is on screen (dialogs, the page's own windows). An ended ad holds no Activity.
     */
    val contextWrapper = MutableContextWrapper(applicationContext)

    val webView = FrameWebView(contextWrapper)

    /** The frame protocol's rules and state for this page. */
    private val protocol = FrameProtocol(adId, Origin.of(url), isVideo, videoDurationMillis, LinkOpener::isAllowed)

    /** Where the page may navigate, and when leaving is a click; it spends the same touches as the protocol. */
    private val navigation = FrameNavigation(protocol.origin, Origin::of, LinkOpener::isAllowed, protocol.touchGate) { protocol.state }

    /** Where the page is, as the protocol says. */
    val state: FramePhase get() = protocol.state
    var failureCause: FailureCause? = null
        private set
    var failureUnderlying: ErrorReport.Underlying? = null
        private set
    var onEvent: ((FrameEvent) -> Unit)? = null

    /** Every message sent to the page, for tests. */
    var onSend: ((String, JsonObject) -> Unit)? = null

    private val origin = protocol.origin
    private var loadTimeoutJob: Job? = null

    /** The WebView's renderer died: the WebView can't be used, only removed and destroyed (see [tearDown]). */
    private var isRendererGone = false

    /** The SDK's own client of the WebView; tests wrap it (WebView.getWebViewClient() exists only from Android 8). */
    internal lateinit var client: WebViewClient
        private set

    init {
        // 1. The WebView's touches and new windows, and what it reports of the page's loads, come here.
        webView.onTouch = ::noteTouch
        webView.onNewWindow = ::externalNavigation
        webView.webViewClient = FrameWebViewClient(
            adId,
            phase = { state },
            navigationDecision = ::navigationDecision,
            onExternalLink = ::externalNavigation,
            onPageReplaced = { fail(FrameEvent.Error(protocol.failureCode), FailureCause.PAGE) },
            onLoadFailed = ::loadFailed,
            onProcessGone = {
                isRendererGone = true
                fail(FrameEvent.Error(protocol.failureCode), FailureCause.PROCESS_TERMINATED)
            },
        ).also { client = it }
        // 2. A page whose renderer stops answering (a script in an endless loop) can no longer close itself, and a
        // linear ad has no other way out: Back is consumed and dismiss() refuses it. The ad ends, failed.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(
                webView,
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                        if (state == FramePhase.ENDED) return
                        Logger.warning { "frame ${adId.take(8)}: the page stopped responding; the ad ends" }
                        fail(FrameEvent.Error(protocol.failureCode), FailureCause.UNRESPONSIVE)
                    }

                    override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
                },
            )
        }
        // 3. The page's messages: `KontextAndroid`, injected for the page's own origin only.
        if (origin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(webView, "KontextAndroid", setOf(origin.rule)) { _, message, sourceOrigin, isMainFrame, _: JavaScriptReplyProxy ->
                    receive(message, Origin.of(sourceOrigin.toString()), isMainFrame)
                }
            } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
                // An origin the WebView refuses: the bridge is never made, so its WebView goes now.
                webView.destroy()
                throw error
            }
        } else {
            Logger.warning { "frame ${adId.take(8)}: this WebView cannot receive the page's messages; the ad will not load" }
        }
    }

    /** Loads the page. `ad.loaded` must arrive within [loadTimeoutMillis] of time the app is in front. */
    fun load() {
        Logger.debug { "frame ${adId.take(8)}: loading $url" }
        webView.loadUrl(url)
        loadTimeoutJob = Environment.scope.launch {
            // The time that passed while the app was in front, measured on the SDK's clock (Environment.now), not
            // the step added per tick: a tick that comes late (a busy main thread) counts as long as it really
            // took. A stretch counts only when the app was in front at both its ends: a process frozen in the
            // background (the cached-app freezer) comes back with one long stretch, begun in the background, that
            // was not time the page had to load.
            val step = minOf(250L, loadTimeoutMillis)
            var waited = 0L
            var last = Environment.now()
            var wasInFront = Environment.isAppInForeground()
            while (waited < loadTimeoutMillis) {
                delay(step)
                val now = Environment.now()
                val isInFront = Environment.isAppInForeground()
                if (isInFront && wasInFront) waited += (now - last).coerceAtLeast(0)
                last = now
                wasInFront = isInFront
            }
            if (state != FramePhase.LOADING) return@launch
            Logger.warning { "frame ${adId.take(8)}: no ad.loaded within ${loadTimeoutMillis / 1000} s" }
            fail(FrameEvent.Error(protocol.loadErrorCode), FailureCause.LOAD_TIMEOUT)
        }
    }

    /**
     * The page is on screen: it starts whatever the creative does when visible, in [size], the variant the SDK
     * chose from the ad's sizes for the real box (dp; `null`: the page fills its view).
     */
    fun show(size: AdPayload.Size? = null) {
        if (!protocol.show()) {
            Logger.warning { "frame ${adId.take(8)}: show() in state $state, ignored" }
            return
        }
        webView.onResume()
        send("ad.show", if (size != null) mapOf("size" to mapOf("width" to size.width, "height" to size.height)) else emptyMap())
    }

    /**
     * How much of the box is on screen: [share] 0–1 from the meter, sent as `exposedPercentage` 0–100, the unit of
     * MRAID's `exposureChange`, which the page relays it to. The rectangle in the page's CSS pixels.
     */
    fun exposure(share: Double, x: Double, y: Double, width: Double, height: Double) {
        if (state != FramePhase.SHOWN) return
        val percentage = Math.round(share * 10_000) / 100.0
        send("ad.exposure", mapOf("exposedPercentage" to percentage, "visibleRectangle" to mapOf("x" to x, "y" to y, "width" to width, "height" to height)))
    }

    /** The page goes on screen in [context] (the container's, or the linear ad's own Activity). */
    fun useShowingContext(context: Context) {
        contextWrapper.baseContext = context
    }

    /** The page is off screen again (a refused show, or the ad ended): the WebView holds no Activity. */
    fun useApplicationContext() {
        contextWrapper.baseContext = applicationContext
    }

    /** The SDK is removing the page: terminal. */
    fun hide() {
        if (state == FramePhase.ENDED) return
        if (state == FramePhase.SHOWN) send("ad.hide")
        end()
    }

    private fun send(type: String, payload: Map<String, Any> = emptyMap()) {
        val message = JsonObject(mapOf("adId" to JsonPrimitive(adId), "type" to JsonPrimitive(type)) + payload.mapValues { toJson(it.value) })
        onSend?.invoke(type, message)
        webView.evaluateJavascript("window.postMessage($message, window.location.origin)", null)
    }

    /** A message of the page: text goes to [FrameProtocol.accept], anything else is a broken envelope from the page. */
    fun receive(message: WebMessageCompat, sender: Origin?, isMainFrame: Boolean) {
        if (message.type == WebMessageCompat.TYPE_STRING) {
            receive(message.data, sender, isMainFrame)
            return
        }
        execute(protocol.acceptNonText(sender, isMainFrame))
    }

    /** A text message of the page, as the protocol rules it. */
    fun receive(raw: String?, sender: Origin?, isMainFrame: Boolean) {
        execute(protocol.accept(raw, sender, isMainFrame, Environment.now()))
    }

    /** The viewer touched the WebView; a URL may be opened for [TouchGate.TOUCH_WINDOW_MILLIS]. */
    fun noteTouch() {
        protocol.touchGate.noteTouch(Environment.now())
    }

    /** What a top-level navigation may do (see [FrameNavigation.navigationDecision]). */
    fun navigationDecision(target: String, isMainFrame: Boolean, current: String?): FrameNavigation.NavigationDecision =
        navigation.navigationDecision(target, isMainFrame, current)

    /** Does what the protocol said to a message of the page. */
    private fun execute(step: FrameProtocol.Step) {
        when (step) {
            FrameProtocol.Step.Ignore -> Unit
            is FrameProtocol.Step.Drop -> {
                if (step.isWarning) Logger.warning { "frame ${adId.take(8)}: ${step.log}" } else Logger.debug { "frame ${adId.take(8)}: ${step.log}" }
                step.violation?.let(::reportProtocolViolation)
            }
            FrameProtocol.Step.Loaded -> {
                loadTimeoutJob?.cancel()
                // A WebView out of any window runs as visible: a preloaded page would draw its animation
                // frames at about 60 fps for as long as the ad is held. Paused until show: animations and
                // rendering stop, JavaScript does not. Only now, after the page said it is ready: a video
                // creative buffers its video first. Never pauseTimers(), which pauses every WebView of the app.
                webView.onPause()
                onEvent?.invoke(FrameEvent.Loaded)
            }
            is FrameProtocol.Step.Deliver -> onEvent?.invoke(step.event)
            is FrameProtocol.Step.EndThenDeliver -> {
                // The page's own ad.error: the page is the cause.
                if (step.event is FrameEvent.Error) failureCause = FailureCause.PAGE
                tearDown()
                onEvent?.invoke(step.event)
            }
        }
    }

    /** A navigation out of our origin is a click the page could not report: honoured only after a touch. */
    private fun externalNavigation(target: String) {
        execute(navigation.externalNavigation(target, Environment.now()))
    }

    private fun fail(event: FrameEvent, cause: FailureCause) {
        if (!protocol.end()) return
        failureCause = cause
        tearDown()
        onEvent?.invoke(event)
    }

    private fun reportProtocolViolation(detail: String) {
        ErrorReporter.report(
            ErrorReport.Code.PROTOCOL_VIOLATION,
            if (state == FramePhase.SHOWN) ErrorReport.Stage.PLAYBACK else ErrorReport.Stage.LOAD,
            adId = adId,
            detail = detail,
        )
    }

    /** Terminal: the page is stopped and nothing is accepted or sent any more. */
    private fun end() {
        if (protocol.end()) tearDown()
    }

    /**
     * The page goes: stopped, unhooked, blanked, and destroyed after the callback that ended it returns. After its
     * renderer died the WebView "can't be used" (onRenderProcessGone): it is only removed and destroyed. Either way
     * the client left on it answers a renderer crash with `true` until the destroy: all of the app's WebViews share
     * one renderer, and a `false` there (the default client's) would take the app down.
     */
    private fun tearDown() {
        loadTimeoutJob?.cancel()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        if (!isRendererGone) {
            webView.stopLoading()
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "KontextAndroid")
            }
            webView.webChromeClient = null
            webView.webViewClient = InertClient()
            webView.loadUrl("about:blank")
        }
        // Not from inside the WebView's own callback (a message, a render-process notice): after it returns.
        android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
    }

    /** A main-frame load that failed before `ad.loaded` fails the ad. */
    private fun loadFailed(underlying: ErrorReport.Underlying) {
        if (state != FramePhase.LOADING) return
        Logger.warning { "frame ${adId.take(8)}: page failed to load (${underlying.domain} ${underlying.code})" }
        failureUnderlying = underlying
        fail(FrameEvent.Error(protocol.loadErrorCode), FailureCause.LOAD_FAILED)
    }

    /** The client of a page that is gone: it survives a renderer crash and lets nothing load but the blank page. */
    private class InertClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString() != "about:blank"

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
    }

    companion object {
        /** `ad.loaded` must arrive within this. A constant in spirit; a variable so the tests can set it. */
        var loadTimeoutMillis = 10_000L

        private fun toJson(value: Any?): JsonElement = when (value) {
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            null -> kotlinx.serialization.json.JsonNull
            else -> JsonPrimitive(value.toString())
        }
    }
}
