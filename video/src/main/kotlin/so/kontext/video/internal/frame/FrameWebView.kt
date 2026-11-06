package so.kontext.video.internal.frame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A WebView locked down for one ad: what the page may and may not do.
 *
 * May: run its scripts, keep DOM storage for its own session, and play media without a tap. May not: read files
 * or content providers, use the cache, zoom, scroll or overscroll, open windows (a `target=_blank` link goes to
 * [onNewWindow] with the URL it asked for, which treats it as any navigation out), show dialogs (answered at once, as dismissed), or reach
 * the camera or microphone (denied). A touch is noted ([onTouch]) without being taken from the page.
 *
 * `FrameBridge` owns one and keeps the rest: the page's client (`FrameWebViewClient`), the messages both ways,
 * the load and its timeout, the failures and the teardown.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class FrameWebView(context: Context) : WebView(context) {
    /** The viewer touched the page. */
    var onTouch: (() -> Unit)? = null

    /** The page asked for a new window to this URL. */
    var onNewWindow: ((String) -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // target=_blank comes to onCreateWindow, where a catcher takes its URL
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            setSupportZoom(false)
        }
        webChromeClient = Chrome()
    }

    /** Notes when the viewer touches the WebView, without taking the touch away from the page. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke()
        return super.dispatchTouchEvent(event)
    }

    private inner class Chrome : WebChromeClient() {
        /**
         * A `target=_blank` link or a `window.open`: no new window, treated like any other navigation out. The
         * callback carries no URL, and the hit test names whatever was last touched (an image's `src` inside a
         * link, nothing for a script's `window.open` from a button), so the standard way is used: the window is
         * granted to a throwaway WebView ([WindowCatcher]) that loads nothing, and the first navigation it is
         * asked for is the URL the page requested.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            transport.webView = WindowCatcher(view.context) { target -> onNewWindow?.invoke(target) }
            resultMsg.sendToTarget()
            return true
        }

        // The page's dialogs are answered at once, as dismissed; no camera or microphone.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            true.also { result.cancel() }

        // A beforeunload prompt would show a native dialog and hold the page; the page leaves at once.
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.confirm() }
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }

    /**
     * The WebView a new window is granted to: it loads nothing (no JavaScript, every navigation refused) and
     * hands the first URL it is asked for to [onTarget], then destroys itself. One that is never asked for a URL
     * goes after [LIFETIME_MILLIS].
     */
    private class WindowCatcher(context: Context, private val onTarget: (String) -> Unit) : WebView(context) {
        private var isDone = false

        init {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    caught(request.url.toString())
                    return true
                }

                // Some WebView versions start a window.open's page without asking first.
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    view.stopLoading()
                    if (url != null && url != "about:blank") caught(url)
                }

                // A popup of the ad page shares its renderer: `false` here would end the app when that renderer
                // dies while the catcher waits. Its URL never comes now; it goes without catching anything.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean = true.also { finish() }
            }
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, LIFETIME_MILLIS)
        }

        fun caught(url: String) {
            if (isDone) return
            onTarget(url)
            finish()
        }

        /** Destroyed once, never from inside its own callback. */
        private fun finish() {
            if (isDone) return
            isDone = true
            Handler(Looper.getMainLooper()).post { destroy() }
        }

        companion object {
            const val LIFETIME_MILLIS = 10_000L
        }
    }
}
