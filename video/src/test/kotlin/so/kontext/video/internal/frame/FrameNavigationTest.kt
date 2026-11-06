package so.kontext.video.internal.frame

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.internal.frame.FrameNavigation.NavigationDecision
import so.kontext.video.internal.frame.FrameProtocol.Step
import java.net.URI

/** The page's navigations as tables, with no WebView: target × phase → decision, and a navigation out as a click. */
class FrameNavigationTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private var now = 1_000_000L
    private var phase = FramePhase.LOADING
    private val touchGate = TouchGate()
    private val navigation = FrameNavigation(originOf(url), ::originOf, { it.startsWith("https://") }, touchGate) { phase }

    /** Origins without Android's Uri: enough for the URLs of these tests. */
    private fun originOf(target: String): Origin? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return Origin(scheme, host, port)
    }

    private fun describe(step: Step): String = when (step) {
        is Step.Drop -> "drop"
        is Step.Deliver -> step.event::class.simpleName!!
        else -> step.toString()
    }

    @Test fun navigation() {
        // target, loading, ready
        val table = listOf(
            Triple("https://frames.test/redirect", NavigationDecision.ALLOW, NavigationDecision.CANCEL),
            Triple("about:blank", NavigationDecision.ALLOW, NavigationDecision.CANCEL),
            Triple("https://brand.test", NavigationDecision.FAIL_LOAD, NavigationDecision.EXTERNAL),
            Triple("$url#card", NavigationDecision.ALLOW, NavigationDecision.ALLOW),
        )
        for ((target, loading, _) in table) assertEquals(loading, navigation.navigationDecision(target, true, url), "loading: $target")
        phase = FramePhase.LOADED
        for ((target, _, ready) in table) assertEquals(ready, navigation.navigationDecision(target, true, url), "ready: $target")
        assertEquals(NavigationDecision.ALLOW, navigation.navigationDecision("https://cdn.test/x", false, url), "a subframe")
        assertNull(originOf("not a url"))
    }

    @Test fun aNavigationOutIsAClickOnScreenAfterOneTouch() {
        touchGate.noteTouch(now)
        phase = FramePhase.LOADED
        assertEquals("drop", describe(navigation.externalNavigation("https://brand.test", now)), "not on screen")
        phase = FramePhase.SHOWN
        assertEquals("drop", describe(navigation.externalNavigation("javascript:x", now)), "a link that may not open")
        assertEquals("Click", describe(navigation.externalNavigation("https://brand.test", now)))
        assertEquals("drop", describe(navigation.externalNavigation("https://brand.test", now)), "the touch is spent")
    }

    @Test fun anInDocumentMoveNeedsTheCurrentDocument() {
        phase = FramePhase.SHOWN
        assertEquals(NavigationDecision.CANCEL, navigation.navigationDecision("$url#card", true, null), "no current URL: not known to be the same document")
        assertEquals(NavigationDecision.CANCEL, navigation.navigationDecision("https://frames.test/other#card", true, url), "another document of the page's origin")
        assertEquals(NavigationDecision.EXTERNAL, navigation.navigationDecision("https://brand.test/#card", true, url), "another origin with a fragment")
        assertEquals(NavigationDecision.ALLOW, navigation.navigationDecision("$url#b", true, "$url#a"), "one fragment to another")
        assertEquals(NavigationDecision.CANCEL, navigation.navigationDecision(url, true, "$url#a"), "back to the same URL without a fragment reloads it")
    }

    @Test fun theOriginIsTheSchemeHostAndPortWhateverTheirCase() {
        val table = listOf(
            "HTTPS://FRAMES.TEST/x" to NavigationDecision.ALLOW,
            "https://frames.test:443/x" to NavigationDecision.ALLOW,
            "http://frames.test/x" to NavigationDecision.FAIL_LOAD,
            "https://frames.test:8443/x" to NavigationDecision.FAIL_LOAD,
            "https://sub.frames.test/x" to NavigationDecision.FAIL_LOAD,
            "javascript:alert(1)" to NavigationDecision.FAIL_LOAD,
            "data:text/html,x" to NavigationDecision.FAIL_LOAD,
        )
        for ((target, expected) in table) assertEquals(expected, navigation.navigationDecision(target, true, url), "loading: $target")
        assertEquals(NavigationDecision.ALLOW, navigation.navigationDecision("javascript:alert(1)", false, url), "a subframe's, while loading too")
    }

    @Test fun aNavigationOutBeforeShowIsDroppedAndKeepsTheTouch() {
        touchGate.noteTouch(now)
        for (before in listOf(FramePhase.LOADING, FramePhase.LOADED, FramePhase.ENDED)) {
            phase = before
            assertEquals("drop", describe(navigation.externalNavigation("https://brand.test", now)), "$before")
        }
        phase = FramePhase.SHOWN
        assertEquals("Click", describe(navigation.externalNavigation("https://brand.test", now)), "the touch was not spent on the refusals")
    }
}
