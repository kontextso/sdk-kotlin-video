package so.kontext.video

/** SDK-wide defaults. Mirrors sdk-swift-video's `Constants`. */
public object Constants {
    /** Production ad-server base URL. */
    public const val DEFAULT_AD_SERVER_URL: String = "https://server.megabrain.co"

    /** `/instream/preload` request timeout. May be overridden by the `/init` response. */
    internal const val DEFAULT_PRELOAD_TIMEOUT_MS: Long = 16_000

    /** `/init` request timeout. */
    internal const val INIT_TIMEOUT_MS: Long = 16_000

    /** Tracking ping timeout. */
    internal const val TRACKING_TIMEOUT_MS: Long = 10_000

    /**
     * How long an interstitial's video has to produce its first frame before
     * the SDK gives up (bad URL, dead CDN, endless buffering) and reports
     * failure so the app is never stuck on a blank screen.
     */
    internal const val INTERSTITIAL_START_TIMEOUT_MS: Long = 8_000

    /**
     * How long a mid-playback stall may last before the SDK reports failure.
     * A stall that recovers within this window is ignored.
     */
    internal const val INTERSTITIAL_STALL_TIMEOUT_MS: Long = 8_000

    /**
     * How long an ad view stays in the hierarchy after `destroy()` when it
     * was on screen, so measurement scripts can send their final beacons;
     * the same grace applies to tracking pings.
     */
    internal const val TEARDOWN_DELAY_MS: Long = 1_000

    internal const val LOG_TAG: String = "KontextVideo"
}
