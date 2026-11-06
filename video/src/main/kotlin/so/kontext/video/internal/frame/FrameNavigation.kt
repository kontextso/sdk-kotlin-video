package so.kontext.video.internal.frame

import so.kontext.video.VideoAd

/**
 * The rules for the page's top-level navigations, with no WebView: where the page may go, and when leaving is a
 * click. While it loads, the page may move within its own origin (a redirect) or to `about:blank`; a move out of
 * its origin then (a cross-origin redirect of its own load) fails the load at once, as `FRAME_LOAD_FAILED`,
 * rather than waiting out the load timeout on a page that will never come; once ready
 * it stays where it is, except for a move within the document (a fragment). A navigation out of its origin is a
 * click the page could not report: honoured only on screen, to a link that may open, right after a touch.
 *
 * ```
 *   target                       loading    loaded, shown
 *   a subframe's                 allow      allow
 *   the same document, #frag     allow      allow
 *   its own origin, about:blank  allow      cancel
 *   any other origin             fail load  external (a click, after a touch; else dropped)
 * ```
 *
 * `FrameWebViewClient` asks [navigationDecision]; `FrameBridge` carries out [externalNavigation]'s step. Tested
 * as a table (`FrameNavigationTest`).
 */
internal class FrameNavigation(
    /** The page's own origin; `null` for a URL with none. */
    private val origin: Origin?,
    /** A URL's origin, for a navigation's target. */
    private val originOf: (String) -> Origin?,
    /** Whether a link may open (`LinkOpener.isAllowed`). */
    private val isAllowed: (String) -> Boolean,
    /** The touch a link needs, shared with [FrameProtocol]. */
    private val touchGate: TouchGate,
    /** Where the page is now. */
    private val phase: () -> FramePhase,
) {
    /** What a top-level navigation of the page may do. */
    enum class NavigationDecision { ALLOW, CANCEL, EXTERNAL, FAIL_LOAD }

    /** What a top-level navigation may do: while loading, its own origin and `about:blank`; once ready only an in-document (fragment) move. */
    fun navigationDecision(target: String, isMainFrame: Boolean, current: String?): NavigationDecision {
        if (!isMainFrame) return NavigationDecision.ALLOW
        val isOurs = originOf(target) == origin || target == "about:blank"
        if (phase() == FramePhase.LOADING) return if (isOurs) NavigationDecision.ALLOW else NavigationDecision.FAIL_LOAD
        if (current != null && '#' in target && target.substringBefore('#') == current.substringBefore('#')) return NavigationDecision.ALLOW
        return if (isOurs) NavigationDecision.CANCEL else NavigationDecision.EXTERNAL
    }

    /** A navigation out of our origin is a click the page could not report: honoured only after a touch. */
    fun externalNavigation(target: String, now: Long): FrameProtocol.Step {
        if (phase() != FramePhase.SHOWN || !isAllowed(target) || !touchGate.consume(now)) {
            return FrameProtocol.Step.Drop("navigation to $target cancelled", isWarning = true)
        }
        return FrameProtocol.Step.Deliver(FrameEvent.Click(target, null, VideoAd.Click.Area.Creative))
    }
}
