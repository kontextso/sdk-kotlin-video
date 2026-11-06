package so.kontext.video.internal.platform

/**
 * Where a link an ad may open goes: into the app it names, into the Play Store, or onto the web.
 *
 * Why: a web link opens in the in-app browser, so the viewer stays in the app and the ad is only covered; a link
 * meant for an app must still reach that app. The scheme and the host decide, before anything opens:
 *
 * ```
 * link                                              route   opened by LinkOpener
 * brandapp://offer, hostapp://premium               APP     the app it names (ACTION_VIEW)
 * market://details?id=…,                            STORE   the Play Store (ACTION_VIEW)
 *   https://play.google.com/store/apps/…
 * https://brand.test/offer, http://brand.test       WEB     the app that claims it (a verified App Link), else
 *                                                           the in-app browser, else the default browser
 * ```
 *
 * Pure: `LinkOpener` asks it for a link `isAllowed` let through; the refused schemes are not its concern.
 */
internal enum class LinkRoute {
    /** A custom scheme: the app registered for it opens it. */
    APP,

    /** The Play Store, by its own scheme or a store web link: an app-install ad's link. */
    STORE,

    /** Any other `http` or `https` link. */
    WEB,
    ;

    companion object {
        /** The Play Store's own scheme. */
        private const val STORE_SCHEME = "market"

        /** The Play Store's web host; its apps' pages are under [STORE_PATH]. */
        private const val STORE_HOST = "play.google.com"
        private const val STORE_PATH = "/store/apps"

        /** The route of a link with [scheme], [host] and [path], as `Uri` parses them. */
        fun of(scheme: String?, host: String?, path: String?): LinkRoute {
            val lowerScheme = scheme?.lowercase()
            if (lowerScheme == STORE_SCHEME) return STORE
            if (lowerScheme != "http" && lowerScheme != "https") return APP
            val isStorePage = host?.lowercase() == STORE_HOST && path.orEmpty().let { it == STORE_PATH || it.startsWith("$STORE_PATH/") }
            return if (isStorePage) STORE else WEB
        }
    }
}
