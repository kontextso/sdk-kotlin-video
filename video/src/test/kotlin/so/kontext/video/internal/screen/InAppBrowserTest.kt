package so.kontext.video.internal.screen

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.platform.LinkSystem

/**
 * An ad's click in its in-app browser: the Custom Tab stops the Activity under it while the app stays in the
 * viewer's hands. Inline, that stop is not another screen of the app (the ad stays, its clocks frozen until the
 * return); linear, it covers the ad's screen (exposure 0, the page pauses, and plays on after). One `click`.
 */
@RunWith(RobolectricTestRunner::class)
class InAppBrowserTest : SdkTest() {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val system by lazy { LinkSystem(app) }
    private var now = 0L

    @Before fun openLinksAsAndroidDoes() {
        Environment.now = { now }
        Environment.context = system
    }

    @After fun restoreInterval() {
        ViewabilityMeter.intervalMillis = 250
    }

    /** A player screen the test drives, and a container in its window, as `container()` makes one. */
    private fun playerScreen(): Pair<ActivityController<Activity>, FrameLayout> {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        view.layout(0, 0, 320, 400)
        idle()
        view.layout(0, 0, 320, 400)
        windowVisibility(view, View.VISIBLE)
        return controller to view
    }

    /** What Android does to the views of an Activity that stops or starts: their window hidden or shown. */
    private fun windowVisibility(view: View, visibility: Int) {
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", visibility)
        view.dispatchWindowVisibilityChanged(visibility)
    }

    private fun click(ad: VideoAd, url: String = "https://brand.test/offer") {
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "$url"""")
    }

    /** The Custom Tab the click started over [activity]. */
    private fun tab(activity: Activity): Intent? = shadowOf(activity).nextStartedActivity?.takeIf { it.hasExtra("android.support.customtabs.extra.SESSION") }

    @Test fun anInlineAdStaysUnderItsOwnClicksTabAndAnotherScreenOfTheAppStillEndsItAfter() {
        // The rule must not rest on the background: the app counts as in front all along (a second window of it, say).
        Environment.isAppInForeground = { true }
        system.browser(customTabs = true)
        for (ad in listOf(overlay(tracking = tracking("click")), pause())) {
            beacons.clear()
            val recorder = Recorder()
            ad.listener = recorder
            val (player, container) = playerScreen()
            ad.show(container)
            click(ad)
            assertEquals("https://brand.test/offer", tab(player.get())?.dataString)
            assertTrue(ad.screen!!.inAppBrowser.isOpen)
            // The tab covers the player: it pauses, then stops.
            player.pause().stop()
            assertFalse("its own tab is not a cover", ad.hasEnded)
            // Back from the tab: the player comes back, the tab is closed.
            player.restart().start().resume()
            assertFalse(ad.screen!!.inAppBrowser.isOpen)
            assertFalse(ad.hasEnded)
            assertEquals("one click", 1, recorder.clicks.size)
            // The overlay carries a click entry, the pause fixture none.
            val clickBeacons = if (ad.tracker.has("click", null)) listOf("click") else emptyList()
            assertEquals("its click beacon, once", clickBeacons, events.filter { it == "click" })
            // Now a screen of the app over the player ends it, as before.
            val cover = Robolectric.buildActivity(Activity::class.java).setup()
            player.pause().stop()
            assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
            cover.pause().stop().destroy()
            player.destroy()
        }
        assertTrue("nothing opened outside the app", system.started.isEmpty())
    }

    @Test fun anOverlaysClocksFreezeWhileItsTabIsUpAndRunOnAfter() {
        ViewabilityMeter.intervalMillis = 1_000_000 // no meter ticks: the clock alone
        system.browser(customTabs = true)
        val recorder = Recorder()
        val ad = overlay(duration = "10000", tracking = tracking("click"))
        ad.listener = recorder
        val (player, container) = playerScreen()
        ad.show(container)
        val host = container.getChildAt(0) as InlineAdView
        now = 3_000
        assertEquals(3_000, ad.onScreenClock!!.elapsedMillis)
        click(ad)
        assertTrue(ad.screen!!.inAppBrowser.isOpen)
        player.pause().stop()
        windowVisibility(host, View.GONE)
        now = 60_000
        idle(57_000)
        assertEquals("the tab's time is not on screen", 3_000, ad.onScreenClock!!.elapsedMillis)
        assertFalse("the overlay's 10 s did not run out", ad.hasEnded)
        // Back: the clock runs on from 3 s.
        player.restart().start().resume()
        windowVisibility(host, View.VISIBLE)
        now = 61_000
        assertEquals(4_000, ad.onScreenClock!!.elapsedMillis)
        assertFalse(ad.hasEnded)
        assertEquals(listOf("click"), events)
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
    }

    @Test fun aLinearAdPausesUnderItsTabAndPlaysOnAfter() {
        ViewabilityMeter.intervalMillis = 1_000_000 // the test ticks the meter itself
        system.browser(customTabs = true)
        val recorder = Recorder()
        val ad = linear(tracking = tracking("click"))
        ad.listener = recorder
        val exposures = mutableListOf<Double>()
        ad.loadedBridge!!.onSend = { type, message -> if (type == "ad.exposure") exposures += message["exposedPercentage"]!!.jsonPrimitive.double }
        val host = activity()
        ad.show(host)
        val screen = Robolectric.buildActivity(LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).create().start().resume().visible()
        idle()
        windowVisibility(screen.get().window.decorView, View.VISIBLE)
        drawn(ad)
        page(ad, "video.start", """"playhead": 0""")
        ad.meter!!.tick()
        assertTrue("playing: $exposures", (exposures.lastOrNull() ?: 0.0) > 50.0)
        click(ad)
        assertEquals("opened over the ad's own screen", "https://brand.test/offer", tab(screen.get())?.dataString)
        screen.pause().stop()
        exposures.clear()
        now += 100
        ad.meter!!.tick()
        assertEquals("the page hears exposure 0 and pauses its video", listOf(0.0), exposures)
        assertFalse(ad.hasEnded)
        screen.restart().start().resume()
        windowVisibility(screen.get().window.decorView, View.VISIBLE)
        drawn(ad)
        now += 100
        ad.meter!!.tick()
        assertTrue("exposed again, the page plays on: $exposures", (exposures.lastOrNull() ?: 0.0) > 50.0)
        assertFalse(ad.hasEnded)
        assertFalse(ad.screen!!.inAppBrowser.isOpen)
        assertEquals(listOf("click"), events)
        assertEquals(1, recorder.clicks.size)
    }

    @Test fun withoutACustomTabsBrowserTheClickOpensTheDefaultBrowser() {
        system.browser(customTabs = false)
        val ad = overlay(tracking = tracking("click"))
        val (player, container) = playerScreen()
        ad.show(container)
        click(ad)
        assertEquals("https://brand.test/offer", system.started.single().dataString)
        assertNull(tab(player.get()))
        assertFalse(ad.screen!!.inAppBrowser.isOpen)
        assertEquals(listOf("click"), events)
    }

    /** The page fills its screen, as a real WebView does: Robolectric's ignores its layout, so its frame is set. */
    private fun drawn(ad: VideoAd) {
        val webView = ad.loadedBridge!!.webView
        var node = webView.parent as? View
        while (node != null) {
            node.layout(0, 0, 320, 480)
            node = node.parent as? View
        }
        ReflectionHelpers.setField(webView, "mRight", 320)
        ReflectionHelpers.setField(webView, "mBottom", 480)
    }
}
