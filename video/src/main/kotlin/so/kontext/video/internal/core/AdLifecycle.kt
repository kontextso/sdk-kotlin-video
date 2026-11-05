package so.kontext.video.internal.core

import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason

/**
 * The rules of an ad's life, and nothing else: its phases and the legal moves between them. Stateless: it holds no
 * ad's facts ([AdState] does) and only answers whether a move is legal. No Android, no side effects.
 *
 * ```
 *              show                attach                 end                onAdEnded
 *   Offered ────────▶ Reserved ──────────▶ OnScreen ──────────▶ Ending ──────────▶ Ended
 *     │  ▲               │                                         ▲
 *     │  └── unreserve ──┤  (the container refused the host)       │
 *     │                  └──────────── ended during the attach ────┘
 *     └──▶ Invalidated  (ended before show: expired, rejected, destroyed, failed, content changed)
 * ```
 *
 * Any other move (a late event, a second end) is refused: that refusal is what keeps the lifecycle guarantee, one
 * `onAdEnded` after a successful show, whoever ends the ad and however often.
 */
internal object AdLifecycle {
    /** Where an ad is in its life. */
    sealed class Phase {
        /** Returned by `format()`; `isLoaded` says whether the creative is ready. */
        object Offered : Phase()

        /** Ended before `show`: nothing was delivered. */
        data class Invalidated(val reason: InvalidationReason) : Phase()

        /** `show` committed and took the app's one slot; a linear ad waits for its screen. */
        object Reserved : Phase()

        /** The creative is on screen: the page was told to show, the meter and the clock run. */
        object OnScreen : Phase()

        /** The ad is ending; a linear ad's screen is closing, and `onAdEnded` follows once it is gone. */
        data class Ending(val reason: EndReason) : Phase()

        /** `onAdEnded` was delivered. */
        data class Ended(val reason: EndReason) : Phase()
    }

    /**
     * Whether [from] may move to [to]: offered → invalidated or reserved; reserved → on screen, ending, or back to
     * offered (the container refused the host, nothing was shown); on screen → ending; ending → ended. Anything else
     * (a late event, a second end) is refused.
     */
    fun canMove(from: Phase, to: Phase): Boolean = when (from) {
        Phase.Offered -> to is Phase.Invalidated || to == Phase.Reserved
        Phase.Reserved -> to == Phase.OnScreen || to is Phase.Ending || to == Phase.Offered
        Phase.OnScreen -> to is Phase.Ending
        is Phase.Ending -> to is Phase.Ended
        is Phase.Invalidated, is Phase.Ended -> false
    }
}
