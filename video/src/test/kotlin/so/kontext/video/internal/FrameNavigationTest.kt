package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.internal.frame.FrameNavigation
import so.kontext.video.internal.frame.FrameNavigation.NavigationDecision
import so.kontext.video.internal.frame.FramePhase
import so.kontext.video.internal.frame.FrameProtocol.Step
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.frame.TouchGate
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
            Triple("https://brand.test", NavigationDecision.EXTERNAL, NavigationDecision.EXTERNAL),
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
}
