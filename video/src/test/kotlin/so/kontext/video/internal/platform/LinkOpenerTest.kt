package so.kontext.video.internal.platform

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import so.kontext.video.internal.screen.InAppBrowser

/**
 * Which links an ad may open, and how they open: a BROWSABLE intent with no component, the fallback after it; a web
 * link in the app that claims it, else the in-app browser, else the default browser (the routes: LinkRouteTest).
 */
@RunWith(RobolectricTestRunner::class)
class LinkOpenerTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** The Activity an ad is shown in, and its in-app browser. */
    private val screen: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val browser by lazy { InAppBrowser { screen } }

    private val Intent.requiresNonBrowser get() = flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER != 0

    /** The Custom Tab the in-app browser started over [screen]. */
    private fun tab(): Intent? = shadowOf(screen).nextStartedActivity?.takeIf { it.hasExtra(CUSTOM_TABS_SESSION) }

    @After fun forgetActivities() = AppActivities.resetForTesting()

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

    // region Routes: where each kind of link opens

    @Test fun storeLinksOpenThePlayStoreAsBefore() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.scheme("com.store.test", "market")
        system.appLink("com.store.test", "play.google.com")
        for (url in listOf("market://details?id=x", "https://play.google.com/store/apps/details?id=x")) {
            assertTrue(url, LinkOpener.open(system, url, null, browser))
            val intent = system.started.removeAt(0)
            assertEquals(url, intent.dataString)
            assertFalse("a plain view: the store takes its own links", intent.requiresNonBrowser)
        }
        assertNull("never the in-app browser", tab())
    }

    @Test fun storeLinksOpenInThePlayStoreAppByNameWhenItIsInstalled() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        // A browser that also claims the store's web links: a plain view could open in it.
        system.appLink("com.browser.test", "play.google.com")
        system.scheme("com.android.vending", "market")
        system.appLink("com.android.vending", "play.google.com")
        for (url in listOf("market://details?id=x", "https://play.google.com/store/apps/details?id=x")) {
            assertTrue(url, LinkOpener.open(system, url, null, browser))
            val intent = system.started.removeAt(0)
            assertEquals(url, intent.dataString)
            assertEquals("the Play Store app by name", "com.android.vending", intent.`package`)
        }
        assertNull(tab())
    }

    @Test fun storeLinksWithoutThePlayStoreAppOpenAsAPlainView() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        val url = "https://play.google.com/store/apps/details?id=x"
        assertTrue(LinkOpener.open(system, url, null, browser))
        val intent = system.started.single()
        assertEquals(url, intent.dataString)
        assertNull("no package: whatever opens it", intent.`package`)
        assertNull("never the in-app browser", tab())
    }

    @Test fun anAppsCustomSchemeOpensThatAppAsBefore() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.scheme("com.brand.app", "brandapp")
        assertTrue(LinkOpener.open(system, "brandapp://product/1", null, browser))
        assertEquals("brandapp://product/1", system.started.single().dataString)
        assertNull(tab())
        assertFalse(browser.isOpen)
    }

    @Test fun aWebLinkAnAppClaimsOpensInThatApp() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.appLink("com.brand.app", "brand.test")
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        val intent = system.started.single()
        assertEquals("https://brand.test/offer", intent.dataString)
        assertTrue("Android 11+: the system keeps browsers out", intent.requiresNonBrowser)
        assertNull(tab())
        assertFalse(browser.isOpen)
    }

    @Test fun aWebLinkNoAppClaimsOpensInTheInAppBrowser() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        assertTrue("nothing outside the app", system.started.isEmpty())
        assertEquals("https://brand.test/offer", tab()!!.dataString)
        assertTrue(browser.isOpen)
    }

    @Test fun withoutACustomTabsBrowserAWebLinkOpensInTheDefaultBrowser() {
        val system = LinkSystem(app)
        system.browser(customTabs = false)
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        val intent = system.started.single()
        assertEquals("https://brand.test/offer", intent.dataString)
        assertFalse(intent.requiresNonBrowser)
        assertNull(tab())
        assertFalse(browser.isOpen)
    }

    @Test fun withoutAnActivityToOpenOverAWebLinkOpensInTheDefaultBrowser() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, InAppBrowser { null }))
        assertEquals("https://brand.test/offer", system.started.single().dataString)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun aDeepLinkNothingOpensFallsBackToItsWebLinkInTheInAppBrowser() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        assertTrue(LinkOpener.open(system, "brandapp://product/1", "https://brand.test/product/1", browser))
        assertTrue(system.started.isEmpty())
        assertEquals("https://brand.test/product/1", tab()!!.dataString)
    }

    @Test fun nothingInstalledOpensNothing() {
        val system = LinkSystem(app)
        assertFalse(LinkOpener.open(system, "https://brand.test/offer", "brandapp://product/1", browser))
        assertTrue(system.started.isEmpty())
        assertNull(tab())
        assertFalse(browser.isOpen)
    }

    @Config(sdk = [26])
    @Test
    fun beforeAndroid11AWebLinkOneAppClaimsOpensInThatAppByName() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.appLink("com.brand.app", "brand.test")
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        val intent = system.started.single()
        assertEquals("the claiming app, not a browser", "com.brand.app", intent.`package`)
        assertFalse("no such flag before Android 11", intent.requiresNonBrowser)
        assertNull(tab())
    }

    @Config(sdk = [26])
    @Test
    fun beforeAndroid11AWebLinkSeveralAppsClaimOpensInTheSystemsChooser() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.appLink("com.brand.app", "brand.test")
        system.appLink("com.other.app", "brand.test")
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        assertNull("the system asks the viewer", system.started.single().`package`)
        assertNull(tab())
    }

    @Config(sdk = [26])
    @Test
    fun beforeAndroid11AWebLinkOnlyBrowsersOpenGoesToTheInAppBrowser() {
        val system = LinkSystem(app)
        system.browser(customTabs = true)
        system.browser(packageName = "com.second.browser")
        assertTrue(LinkOpener.open(system, "https://brand.test/offer", null, browser))
        assertTrue(system.started.isEmpty())
        assertEquals("https://brand.test/offer", tab()!!.dataString)
    }

    // endregion

    private companion object {
        /** The extra every Custom Tabs intent carries. */
        const val CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    }
}
