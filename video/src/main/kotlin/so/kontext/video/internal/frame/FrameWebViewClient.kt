package so.kontext.video.internal.frame

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.Logger

/**
 * The SDK's client of the ad's WebView: what the WebView reports about the page's loads, turned into the
 * bridge's terms. It decides nothing itself: a navigation is decided by the rules (`FrameNavigation`, through
 * [navigationDecision]), and each failure goes back to `FrameBridge`, which ends the page.
 *
 * ```
 *   a navigation              ──► navigationDecision: allow, cancel, or onExternalLink (a click the page could not report)
 *   a new document once ready ──► onPageReplaced
 *   a main-frame load error   ──► onLoadFailed(underlying)
 *   the web process gone      ──► onProcessGone
 * ```
 */
internal class FrameWebViewClient(
    /** The ad's id, for the log. */
    private val adId: String,
    /** Where the page is now. */
    private val phase: () -> FramePhase,
    /** What a top-level navigation may do: (target, isMainFrame, the page's current URL). */
    private val navigationDecision: (String, Boolean, String?) -> FrameNavigation.NavigationDecision,
    /** A navigation out of the page's origin. */
    private val onExternalLink: (String) -> Unit,
    /** The page replaced itself after it was ready. */
    private val onPageReplaced: () -> Unit,
    /** A main-frame load failed. */
    private val onLoadFailed: (ErrorReport.Underlying) -> Unit,
    /** The web content process ended. */
    private val onProcessGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        return when (navigationDecision(target, request.isForMainFrame, view.url)) {
            FrameNavigation.NavigationDecision.ALLOW -> false
            FrameNavigation.NavigationDecision.CANCEL -> {
                Logger.warning { "frame ${adId.take(8)}: the page tried to replace itself after it was ready; refused" }
                true
            }
            FrameNavigation.NavigationDecision.EXTERNAL -> {
                onExternalLink(target)
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
        val phase = phase()
        if (phase == FramePhase.LOADING || phase == FramePhase.ENDED) return
        Logger.warning { "frame ${adId.take(8)}: the page replaced itself after it was ready; the ad ends" }
        view.stopLoading()
        onPageReplaced()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onLoadFailed(ErrorReport.Underlying("webview", error.errorCode))
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) onLoadFailed(ErrorReport.Underlying("http", errorResponse.statusCode))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logger.warning { "frame ${adId.take(8)}: web content process ended" }
        // 405 is a failure while playing; an ad loaded but never shown gets the load error, like any other.
        onProcessGone()
        // The WebView is dead; returning true keeps the app alive.
        return true
    }
}
