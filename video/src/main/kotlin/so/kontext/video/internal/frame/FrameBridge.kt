package so.kontext.video.internal.frame

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
 * Here: the WebView, its settings and clients, the load and its timeout, pausing and resuming the page, what the
 * SDK sends the page, and the teardown.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class FrameBridge(
    /** The application's context: the WebView's base while the ad is not on screen. */
    private val applicationContext: Context,
    val adId: String,
    private val url: String,
    isVideo: Boolean,
    /** The video's length when the server gives one (a linear ad); without it the playhead is capped at a day. */
    videoDurationMillis: Long? = null,
) {
    /** Why the bridge failed: several causes fire the same VAST code, and the error report keeps them apart. */
    enum class FailureCause { PAGE, LOAD_TIMEOUT, LOAD_FAILED, PROCESS_TERMINATED, UNRESPONSIVE }

    /**
     * The WebView's context: the application's while the page loads off screen and after the ad ends, the
     * showing Activity's while it is on screen (dialogs, the page's own windows). An ended ad holds no Activity.
     */
    val contextWrapper = MutableContextWrapper(applicationContext)

    val webView: WebView = TouchNotingWebView(contextWrapper)

    /** The frame protocol's rules and state for this page. */
    private val protocol = FrameProtocol(adId, Origin.of(url), isVideo, videoDurationMillis, Origin::of, LinkOpener::isAllowed)

    /** Where the page is, as the protocol says. */
    val state: PageState get() = protocol.state
    var failureCause: FailureCause? = null
        private set
    var failureUnderlying: ErrorReport.Underlying? = null
        private set
    var onEvent: ((FrameEvent) -> Unit)? = null

    /** Every message sent to the page, for tests. */
    var onSend: ((String, JsonObject) -> Unit)? = null

    private val origin = protocol.origin
    private var loadTimeoutJob: Job? = null

    /** The SDK's own client of the WebView; tests wrap it (WebView.getWebViewClient() exists only from Android 8). */
    internal lateinit var client: WebViewClient
        private set

    init {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // target=_blank comes to onCreateWindow, where it is refused
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            setSupportZoom(false)
        }
        (webView as TouchNotingWebView).onTouch = ::noteTouch
        webView.webViewClient = Client().also { client = it }
        webView.webChromeClient = Chrome()
        // A page whose renderer stops answering (a script in an endless loop) can no longer close itself, and a
        // linear ad has no other way out: Back is consumed and dismiss() refuses it. The ad ends, failed.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(
                webView,
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                        if (state == PageState.ENDED) return
                        Logger.warning { "frame ${adId.take(8)}: the page stopped responding; the ad ends" }
                        fail(FrameEvent.Error(protocol.failureCode), FailureCause.UNRESPONSIVE)
                    }

                    override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
                },
            )
        }
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
            // The time that passed while the app was in front, measured, not the step added per tick: a tick that
            // comes late (a busy main thread) counts as long as it really took.
            val step = minOf(250L, loadTimeoutMillis)
            var waited = 0L
            var last = SystemClock.uptimeMillis()
            while (waited < loadTimeoutMillis) {
                delay(step)
                val now = SystemClock.uptimeMillis()
                if (Environment.isAppInForeground()) waited += now - last
                last = now
            }
            if (state != PageState.LOADING) return@launch
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
        if (state != PageState.SHOWN) return
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
        if (state == PageState.ENDED) return
        if (state == PageState.SHOWN) send("ad.hide")
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

    /** The viewer touched the WebView; a URL may be opened for [FrameProtocol.TOUCH_WINDOW_MILLIS]. */
    fun noteTouch() {
        protocol.noteTouch(Environment.now())
    }

    /** What a top-level navigation may do (see [FrameProtocol.navigationDecision]). */
    fun navigationDecision(target: String, isMainFrame: Boolean, current: String?): FrameProtocol.NavigationDecision =
        protocol.navigationDecision(target, isMainFrame, current)

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
        execute(protocol.externalNavigation(target, Environment.now()))
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
            if (state == PageState.SHOWN) ErrorReport.Stage.PLAYBACK else ErrorReport.Stage.LOAD,
            adId = adId,
            detail = detail,
        )
    }

    /** Terminal: the page is stopped and nothing is accepted or sent any more. */
    private fun end() {
        if (protocol.end()) tearDown()
    }

    /** The page goes: stopped, unhooked, blanked, and destroyed after the callback that ended it returns. */
    private fun tearDown() {
        loadTimeoutJob?.cancel()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "KontextAndroid")
        }
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.loadUrl("about:blank")
        // Not from inside the WebView's own callback (a message, a render-process notice): after it returns.
        android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            return when (navigationDecision(target, request.isForMainFrame, view.url)) {
                FrameProtocol.NavigationDecision.ALLOW -> false
                FrameProtocol.NavigationDecision.CANCEL -> {
                    Logger.warning { "frame ${adId.take(8)}: the page tried to replace itself after it was ready; refused" }
                    true
                }
                FrameProtocol.NavigationDecision.EXTERNAL -> {
                    externalNavigation(target)
                    true
                }
            }
        }

        /**
         * A new document after the page said it was loaded: a reload or a form POST, which never reach
         * [shouldOverrideUrlLoading], or anything else that got past it. The creative and its controls are gone
         * from under an ad that may be on screen: the ad ends, failed. A move within the document (a fragment,
         * `pushState`) starts no page.
         */
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (state == PageState.LOADING || state == PageState.ENDED) return
            Logger.warning { "frame ${adId.take(8)}: the page replaced itself after it was ready; the ad ends" }
            view.stopLoading()
            fail(FrameEvent.Error(protocol.failureCode), FailureCause.PAGE)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) loadFailed(ErrorReport.Underlying("webview", error.errorCode))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame) loadFailed(ErrorReport.Underlying("http", errorResponse.statusCode))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.warning { "frame ${adId.take(8)}: web content process ended" }
            // 405 is a failure while playing; an ad loaded but never shown gets the load error, like any other.
            fail(FrameEvent.Error(protocol.failureCode), FailureCause.PROCESS_TERMINATED)
            // The WebView is dead; returning true keeps the app alive.
            return true
        }
    }

    /** A main-frame load that failed before `ad.loaded` fails the ad. */
    private fun loadFailed(underlying: ErrorReport.Underlying) {
        if (state != PageState.LOADING) return
        Logger.warning { "frame ${adId.take(8)}: page failed to load (${underlying.domain} ${underlying.code})" }
        failureUnderlying = underlying
        fail(FrameEvent.Error(protocol.loadErrorCode), FailureCause.LOAD_FAILED)
    }

    private inner class Chrome : WebChromeClient() {
        // A target=_blank link: no new window, treated like any other navigation out.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val target = view.hitTestResult.extra
            if (target != null) externalNavigation(target)
            return false
        }

        // The page's dialogs are answered at once, as dismissed; no camera or microphone.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            true.also { result.cancel() }
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }

    /** Notes when the viewer touches the WebView, without taking the touch away from the page. */
    @SuppressLint("ViewConstructor")
    private class TouchNotingWebView(context: Context) : WebView(context) {
        var onTouch: (() -> Unit)? = null

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke()
            return super.dispatchTouchEvent(event)
        }
    }

    /** A scheme, host and port, the default port spelled out. */
    data class Origin(val scheme: String, val host: String, val port: Int) {
        val rule: String get() = if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"

        companion object {
            fun of(url: String): Origin? {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
                val port = if (uri.port == -1) defaultPort(scheme) else uri.port
                return Origin(scheme, host, port)
            }

            private fun defaultPort(scheme: String) = when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> -1
            }
        }
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
