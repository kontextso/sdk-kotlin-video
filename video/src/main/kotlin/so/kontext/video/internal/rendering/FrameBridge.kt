package so.kontext.video.internal.rendering

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import so.kontext.video.VideoAd
import so.kontext.video.internal.network.double
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger

/**
 * Owns the WebView of one ad and speaks the Kontext Frame Protocol v2 with its page. The page posts to
 * `window.KontextAndroid.postMessage(json)`, injected by `addWebMessageListener` for the page's own origin
 * only; the bridge answers with `window.postMessage` into the page. One bridge, one ad: it accepts messages
 * only from the main frame of the page it loaded, carrying its own ad id, and only in an order that makes sense.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class FrameBridge(
    context: Context,
    val adId: String,
    private val url: String,
    isVideo: Boolean,
    /** The video's length when the server gives one (a linear ad); without it the playhead is capped at a day. */
    videoDurationMillis: Long? = null,
) {
    enum class State { LOADING, LOADED, SHOWN, ENDED }

    /** Why the bridge failed: several causes fire the same VAST code, and the error report keeps them apart. */
    enum class FailureCause { PAGE, LOAD_TIMEOUT, LOAD_FAILED, PROCESS_TERMINATED, UNRESPONSIVE }

    val webView: WebView = TouchNotingWebView(context)
    var state = State.LOADING
        private set
    var failureCause: FailureCause? = null
        private set
    var failureUnderlying: ErrorReporter.Underlying? = null
        private set
    var onEvent: ((FrameEvent) -> Unit)? = null

    /** Every message sent to the page, for tests. */
    var onSend: ((String, JsonObject) -> Unit)? = null

    /** The variant the SDK chose from the ad's sizes for the real box (dp); the page lays it out. */
    var chosenSize: AdPayload.Size? = null

    private val origin = Origin.of(url)
    private val loadErrorCode = if (isVideo) 400 else 500
    private var loadTimeoutJob: Job? = null
    private var lastTouchAt = Long.MIN_VALUE / 2
    private var reportedProtocolViolation = false

    /** `video.start` arrived: the video's progress, quartiles, pause, resume and complete count only after it. */
    private var videoStarted = false

    /** The highest playhead a page may report, in milliseconds: the video's duration and the grace, or a day. */
    private val playheadLimitMillis = videoDurationMillis?.let { minOf(it.coerceAtLeast(0) + PLAYHEAD_GRACE_MILLIS, DAY_MILLIS) } ?: DAY_MILLIS

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
                        if (state == State.ENDED) return
                        Logger.warning { "frame ${adId.take(8)}: the page stopped responding; the ad ends" }
                        fail(FrameEvent.Error(if (state == State.SHOWN) 405 else loadErrorCode), FailureCause.UNRESPONSIVE)
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
            if (state != State.LOADING) return@launch
            Logger.warning { "frame ${adId.take(8)}: no ad.loaded within ${loadTimeoutMillis / 1000} s" }
            fail(FrameEvent.Error(loadErrorCode), FailureCause.LOAD_TIMEOUT)
        }
    }

    /** The page is on screen: it starts whatever the creative does when visible, in the chosen size. */
    fun show() {
        if (state != State.LOADED) {
            Logger.warning { "frame ${adId.take(8)}: show() in state $state, ignored" }
            return
        }
        state = State.SHOWN
        webView.onResume()
        val size = chosenSize
        send("ad.show", if (size != null) mapOf("size" to mapOf("width" to size.width, "height" to size.height)) else emptyMap())
    }

    /**
     * How much of the box is on screen: [share] 0–1 from the meter, sent as `exposedPercentage` 0–100, the unit of
     * MRAID's `exposureChange`, which the page relays it to. The rectangle in the page's CSS pixels.
     */
    fun exposure(share: Double, x: Double, y: Double, width: Double, height: Double) {
        if (state != State.SHOWN) return
        val percentage = Math.round(share * 10_000) / 100.0
        send("ad.exposure", mapOf("exposedPercentage" to percentage, "visibleRectangle" to mapOf("x" to x, "y" to y, "width" to width, "height" to height)))
    }

    /** The SDK is removing the page: terminal. */
    fun hide() {
        if (state == State.ENDED) return
        if (state == State.SHOWN) send("ad.hide")
        end()
    }

    private fun send(type: String, payload: Map<String, Any> = emptyMap()) {
        val message = JsonObject(mapOf("adId" to JsonPrimitive(adId), "type" to JsonPrimitive(type)) + payload.mapValues { toJson(it.value) })
        onSend?.invoke(type, message)
        webView.evaluateJavascript("window.postMessage($message, window.location.origin)", null)
    }

    /**
     * Checks the envelope, the sender and the order; drops anything else without an error. A message that is
     * not text (an ArrayBuffer) is never read: its `data` accessor throws. From the page itself it is a broken
     * envelope.
     */
    fun receive(message: WebMessageCompat, sender: Origin?, isMainFrame: Boolean) {
        if (message.type == WebMessageCompat.TYPE_STRING) {
            receive(message.data, sender, isMainFrame)
            return
        }
        if (state == State.ENDED || !isMainFrame || sender == null || sender != origin) return
        Logger.debug { "frame ${adId.take(8)}: a message that is not text dropped" }
        reportProtocolViolation("envelope")
    }

    // One branch per message type of the frame protocol.
    @Suppress("CyclomaticComplexMethod")
    fun receive(raw: String?, sender: Origin?, isMainFrame: Boolean) {
        if (state == State.ENDED) return
        if (!isMainFrame || sender == null || sender != origin) {
            Logger.debug { "frame ${adId.take(8)}: message from another frame dropped" }
            return
        }
        val body = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.obj()
        val type = body?.str("type")
        if (body == null || body.str("adId") != adId || type == null) {
            Logger.debug { "frame ${adId.take(8)}: malformed message dropped" }
            reportProtocolViolation("envelope")
            return
        }
        Logger.debug { "frame ${adId.take(8)}: ← $type" }
        // Milliseconds into the ad video, clamped to the video's duration (a day without one): a playhead past
        // the end would fire every checkpoint at once.
        val playhead = (body.double("playhead") ?: 0.0).coerceIn(0.0, playheadLimitMillis.toDouble()).toLong()
        when (type) {
            "ad.loaded" -> {
                if (state != State.LOADING) return
                loadTimeoutJob?.cancel()
                state = State.LOADED
                // A WebView out of any window runs as visible: a preloaded page would draw its animation
                // frames at about 60 fps for as long as the ad is held. Paused until show: animations and
                // rendering stop, JavaScript does not. Only now, after the page said it is ready: a video
                // creative buffers its video first. Never pauseTimers(), which pauses every WebView of the app.
                webView.onPause()
                onEvent?.invoke(FrameEvent.Loaded)
            }
            "ad.click", "ad.open" -> {
                if (state != State.SHOWN) return
                val primary = body.str("url")?.takeIf(::isAllowed)
                val fallback = if (type == "ad.click") body.str("fallbackUrl")?.takeIf(::isAllowed) else null
                // A click whose link may not open still has its fallback, which then is the click-through.
                val target = primary ?: fallback ?: return
                if (!consumeTouch()) {
                    Logger.warning { "frame ${adId.take(8)}: $type to $target dropped, no touch before it" }
                    return
                }
                if (type == "ad.click") {
                    onEvent?.invoke(FrameEvent.Click(target, fallback.takeIf { primary != null }, VideoAd.Click.Area.from(body.str("area"))))
                } else {
                    onEvent?.invoke(FrameEvent.Open(target, body.str("purpose") ?: "unknown"))
                }
            }
            "ad.close" -> {
                if (state != State.SHOWN) return
                end()
                onEvent?.invoke(FrameEvent.Close(FrameEvent.CloseReason.from(body.str("reason"))))
            }
            "video.skip" -> {
                if (state != State.SHOWN) return
                end()
                onEvent?.invoke(FrameEvent.Skip(playhead))
            }
            "video.complete" -> if (state == State.SHOWN && startedBefore(type)) onEvent?.invoke(FrameEvent.Complete(playhead))
            "video.progress" -> {
                if (state != State.SHOWN || !startedBefore(type)) return
                val offset = (body["offset"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (offset == null || offset % 1.0 != 0.0 || offset !in 0.0..DAY_MILLIS.toDouble()) {
                    Logger.debug { "frame ${adId.take(8)}: video.progress with a malformed offset dropped" }
                    reportProtocolViolation("progress.offset")
                    return
                }
                onEvent?.invoke(FrameEvent.Progress(minOf(offset.toLong(), playheadLimitMillis), playhead))
            }
            "ad.error" -> {
                if (state == State.ENDED) return
                // A VAST error code has three digits; any other code from the page goes as 900, undefined error.
                val given = body.double("code")
                val code = given?.let { if (it in 100.0..999.0) it.toInt() else UNDEFINED_ERROR } ?: if (state == State.SHOWN) 405 else loadErrorCode
                fail(FrameEvent.Error(code), FailureCause.PAGE)
            }
            else -> {
                val event = type.takeIf { it.startsWith("video.") }?.let { FrameEvent.VideoEvent.from(it.removePrefix("video.")) }
                if (event == null) {
                    Logger.debug { "frame ${adId.take(8)}: unknown message $type dropped" }
                    return
                }
                if (state == State.SHOWN && accepts(event, type)) onEvent?.invoke(FrameEvent.Video(event, playhead))
            }
        }
    }

    /** The viewer touched the WebView; a URL may be opened for [TOUCH_WINDOW_MILLIS]. */
    fun noteTouch() {
        lastTouchAt = Environment.now()
    }

    /** One tap opens one URL: the first click, open or navigation it authorized spends it. */
    private fun consumeTouch(): Boolean {
        if (Environment.now() - lastTouchAt > TOUCH_WINDOW_MILLIS) return false
        lastTouchAt = Long.MIN_VALUE / 2
        return true
    }

    /** What a top-level navigation may do: while loading, its own origin and `about:blank`; once ready only an in-document (fragment) move. */
    fun navigationDecision(target: String, isMainFrame: Boolean, current: String?): NavigationDecision {
        if (!isMainFrame) return NavigationDecision.ALLOW
        val ours = Origin.of(target) == origin || target == "about:blank"
        if (state == State.LOADING) return if (ours) NavigationDecision.ALLOW else NavigationDecision.EXTERNAL
        if (current != null && '#' in target && target.substringBefore('#') == current.substringBefore('#')) return NavigationDecision.ALLOW
        return if (ours) NavigationDecision.CANCEL else NavigationDecision.EXTERNAL
    }

    enum class NavigationDecision { ALLOW, CANCEL, EXTERNAL }

    /** A navigation out of our origin is a click the page could not report: honoured only after a touch. */
    private fun externalNavigation(target: String) {
        if (state != State.SHOWN || !isAllowed(target) || !consumeTouch()) {
            Logger.warning { "frame ${adId.take(8)}: navigation to $target cancelled" }
            return
        }
        onEvent?.invoke(FrameEvent.Click(target, null, VideoAd.Click.Area.Creative))
    }

    private fun fail(event: FrameEvent, cause: FailureCause) {
        if (state == State.ENDED) return
        failureCause = cause
        end()
        onEvent?.invoke(event)
    }

    /**
     * `video.start` opens the video; mute and unmute are the viewer's and may come while it still loads; the
     * rest need the start before them.
     */
    private fun accepts(event: FrameEvent.VideoEvent, type: String): Boolean = when (event) {
        FrameEvent.VideoEvent.START -> {
            videoStarted = true
            true
        }
        FrameEvent.VideoEvent.MUTE, FrameEvent.VideoEvent.UNMUTE -> true
        else -> startedBefore(type)
    }

    /**
     * Whether `video.start` came before this video message; one that did not is dropped: progress, quartiles
     * or a complete for a video that never started would be billed with no impression.
     */
    private fun startedBefore(type: String): Boolean {
        if (videoStarted) return true
        Logger.debug { "frame ${adId.take(8)}: $type before video.start dropped" }
        reportProtocolViolation("video.beforeStart")
        return false
    }

    private fun reportProtocolViolation(detail: String) {
        if (reportedProtocolViolation) return
        reportedProtocolViolation = true
        ErrorReporter.report(
            ErrorReporter.Code.PROTOCOL_VIOLATION,
            if (state == State.SHOWN) ErrorReporter.Stage.PLAYBACK else ErrorReporter.Stage.LOAD,
            adId = adId,
            detail = detail,
        )
    }

    /** Terminal: the page is stopped and nothing is accepted or sent any more. */
    private fun end() {
        if (state == State.ENDED) return
        state = State.ENDED
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

    /** The SDK's own client of the WebView; tests wrap it (WebView.getWebViewClient() exists only from Android 8). */
    internal lateinit var client: WebViewClient
        private set

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            return when (navigationDecision(target, request.isForMainFrame, view.url)) {
                NavigationDecision.ALLOW -> false
                NavigationDecision.CANCEL -> {
                    Logger.warning { "frame ${adId.take(8)}: the page tried to replace itself after it was ready; refused" }
                    true
                }
                NavigationDecision.EXTERNAL -> {
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
            if (state == State.LOADING || state == State.ENDED) return
            Logger.warning { "frame ${adId.take(8)}: the page replaced itself after it was ready; the ad ends" }
            view.stopLoading()
            fail(FrameEvent.Error(if (state == State.SHOWN) 405 else loadErrorCode), FailureCause.PAGE)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) loadFailed(ErrorReporter.Underlying("webview", error.errorCode))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame) loadFailed(ErrorReporter.Underlying("http", errorResponse.statusCode))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.warning { "frame ${adId.take(8)}: web content process ended" }
            // 405 is a failure while playing; an ad loaded but never shown gets the load error, like any other.
            fail(FrameEvent.Error(if (state == State.SHOWN) 405 else loadErrorCode), FailureCause.PROCESS_TERMINATED)
            // The WebView is dead; returning true keeps the app alive.
            return true
        }
    }

    /** A main-frame load that failed before `ad.loaded` fails the ad. */
    private fun loadFailed(underlying: ErrorReporter.Underlying) {
        if (state != State.LOADING) return
        Logger.warning { "frame ${adId.take(8)}: page failed to load (${underlying.domain} ${underlying.code})" }
        failureUnderlying = underlying
        fail(FrameEvent.Error(loadErrorCode), FailureCause.LOAD_FAILED)
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
        const val TOUCH_WINDOW_MILLIS = 1_000L
        private const val DAY_MILLIS = 86_400_000L

        /** How far past the video's duration a page playhead may go: the page's rounding, not more. */
        private const val PLAYHEAD_GRACE_MILLIS = 1_000L

        /** VAST's undefined error: what a page's code outside 100-999 becomes. */
        private const val UNDEFINED_ERROR = 900

        /**
         * Web, store and the advertiser's app links open. Refused: `intent:`, which could start any Android
         * component; the schemes that call, text or pay without a page in between; and the rest, which are
         * not links to anywhere. Links into the host app open too: which links an ad may carry is the ad server's decision.
         */
        private val refusedSchemes = setOf(
            "intent", "javascript", "data", "file", "blob", "about", "content",
            "tel", "sms", "smsto", "mms", "mmsto",
        )

        /**
         * A link that may open: a usable URL (a scheme, no spaces or control characters, which iOS's URL parsing
         * refuses too, and a host for a web link) whose scheme is not refused.
         */
        fun isAllowed(target: String): Boolean {
            val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(target)?.groupValues?.get(1)?.lowercase() ?: return false
            if (target.any { it <= ' ' || it == '\u007F' }) return false
            if ((scheme == "http" || scheme == "https") && Uri.parse(target).host.isNullOrEmpty()) return false
            return scheme !in refusedSchemes
        }

        private fun toJson(value: Any?): JsonElement = when (value) {
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            null -> kotlinx.serialization.json.JsonNull
            else -> JsonPrimitive(value.toString())
        }
    }
}
