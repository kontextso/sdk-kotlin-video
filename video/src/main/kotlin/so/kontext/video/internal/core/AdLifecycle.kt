package so.kontext.video.internal.core

import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason

/**
 * Where one ad is in its life, and the only way it moves: the functional core of `VideoAd`'s lifecycle. No
 * Android, no side effects: `VideoAd` asks [transition] and acts on its answer (the shell: the page, the screen,
 * the beacons, the listener). Every public flag of the ad (`wasShown`, `hasEnded`, `invalidationReason`) reads
 * from here, live.
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
 * Any other move (a late event, a second end) is refused and changes nothing: that refusal is what keeps the
 * lifecycle guarantee, one `onAdEnded` after a successful show, whoever ends the ad and however often.
 */
internal class AdLifecycle {
    /** Where the ad is now. It moves only through [transition]. */
    var phase: Phase = Phase.Offered
        private set

    /**
     * The creative reached the screen (the phase was [Phase.OnScreen]): `show` attached it and nothing ended it
     * during the attach. Stays true after the end: an ad that was on screen fires its end beacons.
     */
    var isPresented: Boolean = false
        private set

    /** `show` was accepted once. */
    val wasShown: Boolean get() = phase !is Phase.Offered && phase !is Phase.Invalidated

    /** The ad began to end, or has ended. */
    val hasEnded: Boolean get() = phase is Phase.Ending || phase is Phase.Ended

    /** Why the ad ended before it was shown, once it did. */
    val invalidationReason: InvalidationReason? get() = (phase as? Phase.Invalidated)?.reason

    /** Where an ad is in its life. */
    sealed class Phase {
        /** Returned by `format()`; `isLoaded` says whether the creative is ready. */
        object Offered : Phase()

        /** Ended before `show`: nothing was delivered. */
        data class Invalidated(val reason: InvalidationReason) : Phase()

        /** `show` committed and took the app's one slot; a linear ad waits for its screen. */
        object Reserved : Phase()

        /** The creative is presented: the page was told to show, the meter and the clock run. */
        object OnScreen : Phase()

        /** The ad is ending; a linear ad's screen is closing, and `onAdEnded` follows once it is gone. */
        data class Ending(val reason: EndReason) : Phase()

        /** `onAdEnded` was delivered. */
        data class Ended(val reason: EndReason) : Phase()
    }

    /**
     * Moves to [next] when that is a legal step, and says whether it did: offered → invalidated or reserved;
     * reserved → on screen, ending, or back to offered (the container refused the host, nothing was shown);
     * on screen → ending; ending → ended. Anything else (a late event, a second end) changes nothing.
     */
    fun transition(next: Phase): Boolean {
        val legal = when (phase) {
            Phase.Offered -> next is Phase.Invalidated || next == Phase.Reserved
            Phase.Reserved -> next == Phase.OnScreen || next is Phase.Ending || next == Phase.Offered
            Phase.OnScreen -> next is Phase.Ending
            is Phase.Ending -> next is Phase.Ended
            is Phase.Invalidated, is Phase.Ended -> false
        }
        if (!legal) return false
        phase = next
        if (next == Phase.OnScreen) isPresented = true
        return true
    }
}
