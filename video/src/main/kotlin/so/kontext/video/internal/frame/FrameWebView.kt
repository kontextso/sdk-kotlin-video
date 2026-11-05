package so.kontext.video.internal.frame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Message
import android.view.MotionEvent
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * A WebView locked down for one ad: what the page may and may not do.
 *
 * May: run its scripts, keep DOM storage for its own session, and play media without a tap. May not: read files
 * or content providers, use the cache, zoom, scroll or overscroll, open windows (a `target=_blank` link goes to
 * [onNewWindow], which treats it as any navigation out), show dialogs (answered at once, as dismissed), or reach
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
            setSupportMultipleWindows(true) // target=_blank comes to onCreateWindow, where it is refused
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
        // A target=_blank link: no new window, treated like any other navigation out.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val target = view.hitTestResult.extra
            if (target != null) onNewWindow?.invoke(target)
            return false
        }

        // The page's dialogs are answered at once, as dismissed; no camera or microphone.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            true.also { result.cancel() }
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }
}
