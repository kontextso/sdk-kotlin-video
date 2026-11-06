package so.kontext.video.internal.platform

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Which links an ad may open, and how they open: a BROWSABLE intent with no component, the fallback after it. */
@RunWith(RobolectricTestRunner::class)
class LinkOpenerTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun opened(): Intent? = shadowOf(app).nextStartedActivity

    /** An Activity of [packageName] that opens [scheme] links from the web, as an app's deep link declares it. */
    private fun appLink(packageName: String, scheme: String) {
        val component = ComponentName(packageName, "$packageName.LinkActivity")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            },
        )
    }

    @Test fun onlyLinksToSomewhereMayOpen() {
        assertTrue(LinkOpener.isAllowed("https://brand.test"))
        assertTrue(LinkOpener.isAllowed("market://details?id=x"))
        assertFalse(LinkOpener.isAllowed("intent://x#Intent;end"))
        assertFalse(LinkOpener.isAllowed("javascript:alert(1)"))
        assertFalse(LinkOpener.isAllowed("no scheme"))
        assertFalse("a space", LinkOpener.isAllowed("https://brand.test/a b"))
        assertFalse("a web link without a host", LinkOpener.isAllowed("https://"))
    }

    @Test fun everyRefusedSchemeIsRefusedInAnyCase() {
        assertTrue(LinkOpener.isAllowed("https://brand.test"))
        assertTrue(LinkOpener.isAllowed("market://details?id=x"))
        assertTrue("an advertiser's app link", LinkOpener.isAllowed("brandapp://product/1"))
        val refused = listOf(
            "intent://x", "JavaScript:void(0)", "data:text/html,x", "file:///x", "blob:x", "about:blank", "content://x", "no scheme", "1http://x",
            "tel:+15550100", "TEL:1", "sms:+15550100", "smsto:1", "mms:1", "mmsto:1",
        )
        for (refused in refused) {
            assertFalse(refused, LinkOpener.isAllowed(refused))
        }
    }

    @Test fun anAdvertisersDeepLinkOpensAsABrowsableIntentWithNoComponent() {
        appLink("com.brand.app", "brandapp")
        assertTrue(LinkOpener.open(app, "brandapp://product/1", null))
        val intent = opened()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("brandapp://product/1", intent.dataString)
        assertTrue("only what a web link can reach", intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.component)
        assertNull(intent.selector)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun aDeepLinkIntoTheHostAppOpens() {
        // Which links an ad may carry is the ad server's decision; the SDK opens the host app's own too.
        appLink(app.packageName, "publisherapp")
        assertTrue(LinkOpener.open(app, "publisherapp://premium", null))
        val intent = opened()!!
        assertEquals("publisherapp://premium", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun aDeepLinkNothingOpensFallsBackToItsWebLink() {
        shadowOf(app).checkActivities(true)
        appLink("com.browser.test", "https")
        assertTrue(LinkOpener.open(app, "brandapp://product/1", "https://brand.test/product/1"))
        assertEquals("https://brand.test/product/1", opened()!!.dataString)
    }

    @Test fun aRefusedLinkFallsBackAndNothingOpensWithoutAFallback() {
        assertFalse(LinkOpener.open(app, "tel:+15550100", null))
        assertNull(opened())
        assertTrue(LinkOpener.open(app, "intent://x#Intent;scheme=https;end", "https://brand.test/x"))
        assertEquals("https://brand.test/x", opened()?.dataString)
        assertFalse("a refused fallback opens nothing either", LinkOpener.open(app, "tel:1", "sms:1"))
        assertNull(opened())
    }
}
