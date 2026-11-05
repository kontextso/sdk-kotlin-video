package so.kontext.video.internal.frame

/**
 * Where the ad's page is, as [FrameProtocol] moves it: loading, ready off screen, on screen, or gone
 * (terminal). The page's state, not the ad's (`AdState`): see [FrameProtocol] for why there are two and how they
 * move together.
 *
 * ```
 * LOADING ──ad.loaded──► LOADED ──show──► SHOWN
 *    └──────────────────────┴───────────────┴──► ENDED   (an error, the page's close or skip, the SDK's hide)
 * ```
 */
internal enum class PageState { LOADING, LOADED, SHOWN, ENDED }
