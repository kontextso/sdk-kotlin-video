package so.kontext.video.internal.frame

/**
 * Where the ad's frame (its web page) is, as [FrameProtocol] moves it: loading, ready off screen, on screen, or
 * gone (terminal). The frame's phase, not the ad's state: see `AdState` for why there are two and where they move
 * together.
 *
 * ```
 * LOADING ──ad.loaded──► LOADED ──show──► SHOWN
 *    └──────────────────────┴───────────────┴──► ENDED   (an error, the page's close or skip, the SDK's hide)
 * ```
 */
internal enum class FramePhase { LOADING, LOADED, SHOWN, ENDED }
