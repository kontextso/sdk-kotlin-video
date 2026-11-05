package so.kontext.video.internal.state

import so.kontext.video.KontextVideo.State

/**
 * The rules of the SDK's own life, and nothing else: the legal moves between its phases (the public
 * `KontextVideo.State`). Stateless: it holds no fact ([SdkState] does) and only answers whether a move is legal.
 * No Android, no side effects.
 *
 * ```
 *                   initialize               /config: enabled
 *   NotInitialized ───────────▶ Initializing ───────────────────▶ Enabled
 *         │                       │      ▲  │                        │
 *         │       /config failed  ▼      │  │ /config: disabled      │ an ads answer's kill switch
 *         │                     Failed ──┘  ▼                        ▼
 *         │                  the next ads   Disabled ◀───────────────┘
 *         │                  request          ▲  └─┐ another answer's kill switch, another reason
 *         └── initialize in another process ──┘ ◀──┘
 * ```
 *
 * `Disabled` is final but for its reason: no `/config` follows it, and a second `initialize` is ignored in every
 * state but `NotInitialized` (`SdkState.current.isInitialized`). `Failed` moves only back to `Initializing`: the kill switch
 * arrives in an ads answer, and no ads request leaves while `/config` has not enabled the SDK.
 */
internal object SdkLifecycle {
    /**
     * Whether [from] may move to [to]: not initialized → initializing, or disabled (another process);
     * initializing → enabled, disabled or failed (`/config`'s answer); failed → initializing (the retry);
     * enabled → disabled (the kill switch); disabled → disabled with another reason. Anything else is refused.
     */
    fun canMove(from: State, to: State): Boolean = when (from) {
        State.NotInitialized -> to == State.Initializing || to is State.Disabled
        State.Initializing -> to == State.Enabled || to == State.Failed || to is State.Disabled
        State.Failed -> to == State.Initializing
        State.Enabled -> to is State.Disabled
        is State.Disabled -> to is State.Disabled && to != from
    }
}
