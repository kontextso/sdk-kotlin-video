package so.kontext.video.internal.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where a link goes, as a table: its scheme, host and path → the app it names, the Play Store or the web. */
class LinkRouteTest {
    @Test fun everyKindOfLink() {
        val table = listOf(
            // scheme, host, path → route
            Triple("brandapp", "offer", null) to LinkRoute.APP,
            Triple("hostapp", "premium", null) to LinkRoute.APP,
            Triple("market", "details", null) to LinkRoute.STORE,
            Triple("MARKET", "details", null) to LinkRoute.STORE,
            Triple("https", "play.google.com", "/store/apps/details") to LinkRoute.STORE,
            Triple("http", "play.google.com", "/store/apps") to LinkRoute.STORE,
            Triple("https", "PLAY.GOOGLE.COM", "/store/apps/dev") to LinkRoute.STORE,
            Triple("https", "play.google.com", "/store/books/details") to LinkRoute.WEB,
            Triple("https", "play.google.com", "/store/appsx") to LinkRoute.WEB,
            Triple("https", "play.google.com.brand.test", "/store/apps/details") to LinkRoute.WEB,
            Triple("https", "brand.test", "/offer") to LinkRoute.WEB,
            Triple("HTTP", "brand.test", null) to LinkRoute.WEB,
            Triple("https", "brand.test", "/store/apps/details") to LinkRoute.WEB,
        )
        for ((given, expected) in table) {
            val (scheme, host, path) = given
            assertEquals(expected, LinkRoute.of(scheme, host, path), "$scheme://$host$path")
        }
    }
}
