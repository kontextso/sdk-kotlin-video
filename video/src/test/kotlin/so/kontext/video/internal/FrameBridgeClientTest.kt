package so.kontext.video.internal

import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.FramePhase
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.platform.Environment

/** What only a WebView calls on the bridge: navigation decisions, load errors, a dead renderer, dialogs. */
@RunWith(RobolectricTestRunner::class)
class FrameBridgeClientTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val events = mutableListOf<FrameEvent>()
    private lateinit var bridge: FrameBridge

    @Before fun make() {
        Environment.context = ApplicationProvider.getApplicationContext()
        bridge = FrameBridge(Environment.context!!, "ad-1", url, isVideo = false)
        bridge.onEvent = { events += it }
    }

    @After fun reset() = Environment.reset()

    private fun request(target: String, mainFrame: Boolean = true): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(target)
        every { isForMainFrame } returns mainFrame
    }

    private fun loaded() = bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", Origin.of(url), true)

    @Test fun navigationsGoWhereTheStateAllows() {
        val client = bridge.webView.webViewClient
        assertFalse("its own origin while loading", client.shouldOverrideUrlLoading(bridge.webView, request("https://frames.test/next")))
        assertFalse("a subframe navigates freely", client.shouldOverrideUrlLoading(bridge.webView, request("https://cdn.test", mainFrame = false)))
        loaded()
        bridge.show()
        assertTrue("replacing itself once ready", client.shouldOverrideUrlLoading(bridge.webView, request("https://frames.test/other")))
        bridge.noteTouch()
        assertTrue(client.shouldOverrideUrlLoading(bridge.webView, request("https://brand.test/landing")))
        assertEquals("a touched navigation out is a click", "https://brand.test/landing", (events.last() as FrameEvent.Click).url)
    }

    @Test fun aLoadRedirectedToAnotherOriginFailsAtOnce() {
        val client = bridge.webView.webViewClient
        assertTrue("refused", client.shouldOverrideUrlLoading(bridge.webView, request("https://other.test/page")))
        assertEquals("failed now, not at the timeout", FrameBridge.FailureCause.LOAD_FAILED, bridge.failureCause)
        assertEquals(FramePhase.ENDED, bridge.state)
        assertEquals(500, (events.single() as FrameEvent.Error).code)
    }

    @Test fun aNewWindowOpensTheUrlThePageAskedFor() {
        loaded()
        bridge.show()
        bridge.noteTouch()
        val webView = bridge.webView
        val transport = webView.WebViewTransport()
        val message = android.os.Message.obtain(android.os.Handler(android.os.Looper.getMainLooper())).also { it.obj = transport }
        assertTrue("the window is granted, to a catcher", webView.webChromeClient!!.onCreateWindow(webView, false, true, message))
        val catcher = transport.webView!!
        assertTrue("the catcher loads nothing", catcher.webViewClient.shouldOverrideUrlLoading(catcher, request("https://brand.test/landing")))
        assertEquals("https://brand.test/landing", (events.last() as FrameEvent.Click).url)
        // A second navigation of the same window is not a second click.
        catcher.webViewClient.shouldOverrideUrlLoading(catcher, request("https://brand.test/other"))
        assertEquals(1, events.count { it is FrameEvent.Click })
    }

    @Test fun aCatcherSurvivesARendererCrashAndCatchesNothingAfter() {
        loaded()
        bridge.show()
        val webView = bridge.webView
        val transport = webView.WebViewTransport()
        val message = android.os.Message.obtain(android.os.Handler(android.os.Looper.getMainLooper())).also { it.obj = transport }
        webView.webChromeClient!!.onCreateWindow(webView, false, true, message)
        val catcher = transport.webView!!
        assertTrue("the catcher shares the page's renderer: false would stop the app", catcher.webViewClient.onRenderProcessGone(catcher, mockk(relaxed = true)))
        catcher.webViewClient.shouldOverrideUrlLoading(catcher, request("https://brand.test/landing"))
        assertTrue("a dead catcher hands nothing on", events.none { it is FrameEvent.Click })
    }

    @Test fun aDeadRendererIsNeverUsedAgainAndTheLeftClientSurvivesAnother() {
        bridge.load()
        val webView = bridge.webView
        val client = webView.webViewClient
        assertTrue(client.onRenderProcessGone(webView, mockk(relaxed = true)))
        assertEquals("nothing loaded into the dead WebView", url, org.robolectric.Shadows.shadowOf(webView).lastLoadedUrl)
        assertTrue("a second crash before the destroy is survived", webView.webViewClient.onRenderProcessGone(webView, mockk(relaxed = true)))
    }

    @Test fun aHiddenPagesClientSurvivesARendererCrash() {
        loaded()
        bridge.hide()
        val webView = bridge.webView
        assertEquals("about:blank", org.robolectric.Shadows.shadowOf(webView).lastLoadedUrl)
        assertTrue("all WebViews share a renderer: false would stop the app", webView.webViewClient.onRenderProcessGone(webView, mockk(relaxed = true)))
        assertTrue("it loads nothing", webView.webViewClient.shouldOverrideUrlLoading(webView, request("https://brand.test")))
    }

    @Test fun aMainFrameErrorWhileLoadingFailsTheAd() {
        val error: WebResourceError = mockk { every { errorCode } returns -2 }
        bridge.webView.webViewClient.onReceivedError(bridge.webView, request(url), error)
        assertEquals(500, (events.single() as FrameEvent.Error).code)
        assertEquals(FrameBridge.FailureCause.LOAD_FAILED, bridge.failureCause)
        assertEquals(so.kontext.video.internal.diagnostics.ErrorReport.Underlying("webview", -2), bridge.failureUnderlying)
    }

    @Test fun aSubframeErrorOrOneAfterLoadIsNotAFailure() {
        val error: WebResourceError = mockk { every { errorCode } returns -2 }
        bridge.webView.webViewClient.onReceivedError(bridge.webView, request("https://cdn.test/x.js", mainFrame = false), error)
        loaded()
        bridge.webView.webViewClient.onReceivedError(bridge.webView, request(url), error)
        assertEquals(listOf<FrameEvent>(FrameEvent.Loaded), events)
    }

    @Test fun anErrorStatusForThePageFailsTheAd() {
        val response = WebResourceResponse("text/html", "UTF-8", 404, "Not Found", emptyMap(), null)
        bridge.webView.webViewClient.onReceivedHttpError(bridge.webView, request(url), response)
        assertEquals(FrameBridge.FailureCause.LOAD_FAILED, bridge.failureCause)
        assertEquals(so.kontext.video.internal.diagnostics.ErrorReport.Underlying("http", 404), bridge.failureUnderlying)
    }

    @Test fun aDeadRendererFailsTheAdAndKeepsTheAppAlive() {
        loaded()
        bridge.show()
        val detail: RenderProcessGoneDetail = mockk(relaxed = true)
        assertTrue(bridge.webView.webViewClient.onRenderProcessGone(bridge.webView, detail))
        assertEquals(405, (events.last() as FrameEvent.Error).code)
        assertEquals(FrameBridge.FailureCause.PROCESS_TERMINATED, bridge.failureCause)
    }

    @Test fun aNewDocumentAfterLoadEndsTheAd() {
        val client = bridge.webView.webViewClient
        client.onPageStarted(bridge.webView, url, null) // the first load: fine
        assertTrue(events.isEmpty())
        loaded()
        client.onPageStarted(bridge.webView, "https://frames.test/replaced", null)
        assertEquals(500, (events.last() as FrameEvent.Error).code)
        assertEquals(FramePhase.ENDED, bridge.state)
    }

    @Test fun dialogsAreAnsweredAsDismissedAndPermissionsDenied() {
        val chrome = bridge.webView.webChromeClient!!
        val alert: JsResult = mockk(relaxed = true)
        val confirm: JsResult = mockk(relaxed = true)
        val prompt: JsPromptResult = mockk(relaxed = true)
        assertTrue(chrome.onJsAlert(bridge.webView, url, "hi", alert))
        assertTrue(chrome.onJsConfirm(bridge.webView, url, "ok?", confirm))
        assertTrue(chrome.onJsPrompt(bridge.webView, url, "name?", "", prompt))
        verify { alert.cancel() }
        verify { confirm.cancel() }
        verify { prompt.cancel() }
        val beforeUnload: JsResult = mockk(relaxed = true)
        assertTrue("no beforeunload dialog", chrome.onJsBeforeUnload(bridge.webView, url, "leave?", beforeUnload))
        verify { beforeUnload.confirm() }
        val permission: PermissionRequest = mockk(relaxed = true)
        chrome.onPermissionRequest(permission)
        verify { permission.deny() }
        assertFalse("no new window", chrome.onCreateWindow(bridge.webView, false, true, null))
    }
}
