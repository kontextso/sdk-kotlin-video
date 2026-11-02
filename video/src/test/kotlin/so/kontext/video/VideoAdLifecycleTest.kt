package so.kontext.video

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.LinearAdActivity
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.AdPayload

/** One ad from show to its end: the page's events, the beacons they fire, and what the listener hears. */
@RunWith(RobolectricTestRunner::class)
class VideoAdLifecycleTest : SdkTest() {
    private inline fun <reified T : VideoAdException> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: VideoAdException) {
            if (error is T) return error
            fail("threw ${error::class.simpleName}, expected ${T::class.simpleName}")
        }
        fail("threw nothing, expected ${T::class.simpleName}")
        error("unreachable")
    }

    private fun opened(): Intent? = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity

    // region Showing an overlay or pause ad

    @Test fun anImageOverlayRecordsItsImpressionAtShow() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("creativeView", "iconView", "impression"))
        ad.listener = recorder
        ad.show(container())
        assertTrue(ad.wasShown)
        assertSame(ad, Sdk.adOnScreen)
        assertEquals("the impression waits for the first layout", listOf("creativeView", "iconView"), events)
        laidOut()
        assertEquals(listOf("creativeView", "iconView", "impression"), events)
        assertEquals(listOf("impression"), recorder.heard)
        assertEquals(FrameBridge.State.SHOWN, ad.loadedBridge?.state)
    }

    @Test fun aContainerThatGivesTheAdNoRoomEndsItWithoutAnImpression() {
        val warnings = mutableListOf<String>()
        Logger.sink = { level, message -> if (level == Configuration.LogLevel.WARNING) warnings += message }
        // A vertical LinearLayout appends the ad after the player, which takes the whole height.
        val frame = container()
        val column = LinearLayout(frame.context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(frame.context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        laidOut()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(column, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("creativeView", "impression", "notViewable"))
        ad.listener = recorder
        ad.show(column)
        assertTrue("said at show", warnings.any { "LinearLayout" in it })
        laidOut()
        assertEquals(listOf("ended"), recorder.heard)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals("never drawn: no impression, so no verdict", listOf("creativeView"), events)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aVerdictFollowsOnlyAnImpression() {
        // A video overlay nobody started (autoplay refused, no tap): no impression, so no verdict.
        val payload = Fixtures.overlay(tracking = tracking("creativeView", "impression", "notViewable")).replace("\"creative\": \"image\"", "\"creative\": \"video\"")
        val video = loaded(AdPayload.parse(Fixtures.json(payload)))
        video.show(container())
        laidOut()
        video.dismiss()
        assertEquals("unplayed and unbilled: no notViewable", listOf("creativeView"), events)
        // An image overlay counted at its first layout and dismissed before 1 s owes its notViewable.
        beacons.clear()
        val image = overlay(tracking = tracking("creativeView", "impression", "notViewable"))
        image.show(container())
        laidOut()
        image.dismiss()
        assertEquals(listOf("creativeView", "impression", "notViewable"), events)
    }

    @Test fun anAdEndedBeforeItsFirstLayoutNeverCountsAnImpression() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("impression"))
        ad.listener = recorder
        val container = container()
        ad.show(container)
        ad.dismiss()
        laidOut()
        container.layout(0, 0, 320, 400)
        laidOut()
        assertEquals(listOf("ended"), recorder.heard)
        assertTrue(events.isEmpty())
    }

    @Test fun dismissEndsItAtOnceWithItsViewDuration() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("overlayViewDuration"))
        ad.listener = recorder
        val container = container()
        ad.show(container)
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertTrue(ad.hasEnded)
        assertNull(Sdk.adOnScreen)
        assertEquals("the host left the container", 0, container.childCount)
        assertEquals(listOf("overlayViewDuration"), events)
        assertNull("the listener is let go", ad.listener)
        ad.dismiss() // after the end: nothing
        assertEquals(1, recorder.reasons.size)
    }

    @Test fun anOverlayWithoutAPlayerEndsWhenItsDurationElapses() {
        val recorder = Recorder()
        val ad = overlay(duration = "1000")
        ad.listener = recorder
        ad.show(container())
        idle(500)
        assertFalse(ad.hasEnded)
        idle(600)
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.isCompleted)
    }

    @Test fun theOverlaysCloseControlEndsItAsClosed() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("close"))
        ad.listener = recorder
        ad.show(container())
        page(ad, "ad.close")
        assertEquals(listOf(VideoAd.EndReason.CLOSED), recorder.reasons)
        assertTrue("close" in events)
    }

    @Test fun aFullScreenPauseAdsResumeEndsItAsResumed() {
        val recorder = Recorder()
        val ad = pause(position = "fullscreen")
        ad.listener = recorder
        ad.show(container())
        page(ad, "ad.close", """"reason": "resume"""")
        assertEquals(listOf(VideoAd.EndReason.RESUMED), recorder.reasons)
    }

    @Test fun thePageEndingItselfIsCompletedWithoutAClose() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("close"))
        ad.listener = recorder
        ad.show(container())
        page(ad, "ad.close", """"reason": "complete"""")
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertFalse("nobody closed anything", "close" in events)
    }

    @Test fun aClickAfterATouchFiresItsBeaconOpensTheTargetAndTellsTheListener() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("click"))
        ad.listener = recorder
        ad.show(container())
        page(ad, "ad.click", """"url": "https://brand.test/?fid=[KONTEXT_FIREID]", "area": "cta"""")
        assertTrue("no touch, no click", recorder.clicks.isEmpty())
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "https://brand.test/?fid=[KONTEXT_FIREID]", "area": "cta"""")
        assertEquals(listOf(VideoAd.Click(VideoAd.Click.Area.Other("cta"))), recorder.clicks)
        assertEquals(1, events.count { it == "click" })
        val intent = opened()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue("the click-through carries a fire id", intent.dataString!!.startsWith("https://brand.test/?fid=") && "KONTEXT" !in intent.dataString!!)
    }

    // region Click-throughs: what may open

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** An Activity of [packageName] that opens [scheme] links from the web, as an app's deep link declares it. */
    private fun appLink(packageName: String, scheme: String) {
        val component = android.content.ComponentName(packageName, "$packageName.LinkActivity")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            },
        )
    }

    private fun click(ad: VideoAd, url: String, fallback: String? = null) {
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "$url"${fallback?.let { """, "fallbackUrl": "$it"""" } ?: ""}""")
    }

    @Test fun anAdvertisersDeepLinkOpensAsABrowsableIntentWithNoComponent() {
        appLink("com.brand.app", "brandapp")
        val ad = overlay()
        ad.show(container())
        click(ad, "brandapp://product/1")
        val intent = opened()!!
        assertEquals("brandapp://product/1", intent.dataString)
        assertTrue("only what a web link can reach", intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.component)
        assertNull(intent.selector)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun aDeepLinkIntoTheHostAppOpens() {
        // Which links an ad may carry is the ad server's decision; the SDK opens the host app's own too.
        appLink(app.packageName, "publisherapp")
        val ad = overlay()
        ad.show(container())
        click(ad, "publisherapp://premium")
        val intent = opened()!!
        assertEquals("publisherapp://premium", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun callTextAndIntentLinksAreRefusedAndAnHttpFallbackOpensInstead() {
        val ad = overlay()
        ad.show(container())
        for (refused in listOf("tel:+15550100", "sms:+15550100", "smsto:1", "mms:1", "intent://x#Intent;scheme=https;end")) {
            click(ad, refused)
            assertNull(refused, opened())
            click(ad, refused, fallback = "https://brand.test/$refused")
            assertEquals(refused, "https://brand.test/$refused", opened()?.dataString)
        }
    }

    @Test fun aClickWithAnUnusableUrlOpensItsFallback() {
        val ad = overlay(tracking = tracking("click"))
        ad.show(container())
        // No url at all, and one iOS cannot parse either (a space, a web link without a host): the fallback opens.
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"fallbackUrl": "https://brand.test/missing"""")
        assertEquals("https://brand.test/missing", opened()?.dataString)
        for (unusable in listOf("https://brand.test/a b", "https://", "https:///path")) {
            click(ad, unusable, fallback = "https://brand.test/fallback")
            assertEquals(unusable, "https://brand.test/fallback", opened()?.dataString)
        }
        assertEquals("each one a click", List(4) { "click" }, events)
        // Without a usable fallback it is no click at all.
        click(ad, "https://brand.test/a b")
        assertNull(opened())
        assertEquals(4, events.size)
    }

    @Test fun aDeepLinkNothingOpensFallsBackToItsWebLink() {
        shadowOf(app).checkActivities(true)
        appLink("com.browser.test", "https")
        val ad = overlay()
        ad.show(container())
        click(ad, "brandapp://product/1", fallback = "https://brand.test/product/1")
        assertEquals("https://brand.test/product/1", opened()!!.dataString)
    }

    // endregion

    @Test fun theAdChoicesLinkFiresTheIconClickAndOpens() {
        val ad = overlay(tracking = tracking("iconClick", "click"))
        ad.show(container())
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.open", """"url": "https://adchoices.test", "purpose": "adChoices"""")
        assertEquals(listOf("iconClick"), events)
        assertEquals("https://adchoices.test", opened()!!.dataString)
    }

    @Test fun aCreativeThatBreaksOnScreenFailsThenEnds() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("error"))
        ad.listener = recorder
        ad.show(container())
        laidOut()
        page(ad, "ad.error", """"code": 402""")
        assertEquals(listOf("impression", "failed", "ended"), recorder.heard)
        assertEquals(402, (recorder.errors.single() as VideoAdException.CreativeFailed).code)
        assertEquals(listOf(VideoAd.EndReason.FAILED), recorder.reasons)
        assertTrue(beacons.single { "e=error" in it }.isNotEmpty())
    }

    @Test fun aCreativeThatDiesBeforeShowEndsTheAdAndShowSaysSo() {
        val ad = overlay()
        page(ad, "ad.error", """"code": 405""")
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(405), ad.invalidationReason)
        val error = assertThrows<VideoAdException.Invalidated> { ad.show(container()) }
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(405), error.reason)
    }

    // endregion

    // region What show refuses

    @Test fun theWrongPresentationStopsADebugBuildAndThrowsInRelease() {
        val asserted = mutableListOf<String>()
        Environment.assertionFailure = { asserted += it }
        val linear = linear()
        assertThrows<VideoAdException.WrongPresentation> { linear.show(container()) }
        assertThrows<VideoAdException.WrongPresentation> { overlay().show(activity()) }
        assertEquals("each a programmer error at the call", 2, asserted.size)
    }

    @Test fun dismissOnALinearAdStopsADebugBuildAndDoesNothingInRelease() {
        val asserted = mutableListOf<String>()
        Environment.assertionFailure = { asserted += it }
        val recorder = Recorder()
        val linear = linear()
        linear.listener = recorder
        linear.dismiss() // no throw
        assertEquals(1, asserted.size)
        assertTrue(linear.isReady)
        assertTrue(recorder.heard.isEmpty())
    }

    @Test fun rejectNeverThrowsAndDoesNothingOnAShownOrEndedAd() {
        val shown = overlay()
        shown.show(container())
        shown.reject() // after show: nothing
        assertTrue(shown.wasShown && !shown.hasEnded)
        shown.dismiss()
        shown.reject() // after the end: nothing
        val rejected = pause()
        rejected.reject()
        rejected.reject() // already rejected: nothing
        assertEquals(VideoAd.InvalidationReason.Rejected, rejected.invalidationReason)
    }

    @Test fun showRefusesAnAdNotLoaded() {
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
        assertThrows<VideoAdException.NotLoaded> { ad.show(container()) }
        assertFalse(ad.wasShown)
    }

    @Test fun showRefusesASecondShowAndASecondAdOnScreen() {
        val owner = owner()
        val first = overlay(owner)
        val second = pause(owner)
        val container = container()
        first.show(container)
        assertThrows<VideoAdException.AnotherAdShowing> { second.show(container) }
        assertTrue("the other ad stays ready", second.isReady)
        first.dismiss()
        assertThrows<VideoAdException.AlreadyShown> { first.show(container) }
        first.reject() // does nothing after show
        second.show(container)
        assertSame(second, Sdk.adOnScreen)
    }

    @Test fun showRefusesAContainerNotOnScreen() {
        val detached = FrameLayout(ApplicationProvider.getApplicationContext())
        assertThrows<VideoAdException.PresenterUnavailable> { overlay().show(detached) }
        val hidden = container().apply { visibility = android.view.View.INVISIBLE }
        assertThrows<VideoAdException.PresenterUnavailable> { overlay().show(hidden) }
    }

    @Test fun showRefusesAContainerTooSmallAndSaysTheMinimum() {
        val density = ApplicationProvider.getApplicationContext<android.app.Application>().resources.displayMetrics.density
        val ad = overlay()
        val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container(width = 100, height = 400)) }
        assertEquals("half of the smallest variant's 320 dp", Math.ceil(320 * density * 0.5).toInt(), error.minimumWidthPx)
        assertTrue(ad.isReady)
    }

    @Test fun insetsThatLeaveNoRoomAreTooSmall() {
        assertThrows<VideoAdException.ContainerTooSmall> {
            overlay().show(container(), androidx.core.graphics.Insets.of(0, 0, 0, 1_000))
        }
    }

    @Test fun showRefusesAnOverlayOverPausedContent() {
        val owner = owner()
        owner.track(object : VideoAdsPlayer {
            override val currentPositionMillis = 0L
            override val isPlaying = false
        })
        val ad = overlay(owner)
        assertThrows<VideoAdException.ContentPaused> { ad.show(container()) }
        assertTrue(ad.isReady)
    }

    @Test fun showRefusesAfterTheSdkWasSwitchedOff() {
        val ad = overlay()
        Sdk.disable("paused")
        assertThrows<VideoAdException.Disabled> { ad.show(container()) }
    }

    // endregion

    // region Loading

    @Test fun loadWaitsForThePageAndAnotherLoadJoinsIt() {
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay(tracking = tracking("loaded")))), Environment.now(), owner())
        var first: Result<Unit>? = null
        var second: Result<Unit>? = null
        Environment.scope.launch { first = runCatching { ad.load() } }
        Environment.scope.launch { second = runCatching { ad.load() } }
        idle()
        assertNull(first)
        page(ad, "ad.loaded")
        idle()
        assertTrue(first!!.isSuccess && second!!.isSuccess)
        assertTrue(ad.isLoaded && ad.isReady)
        assertEquals(listOf("loaded"), events)
        Environment.scope.launch { ad.load() } // loaded already: returns
        idle()
    }

    @Test fun aPageThatNeverLoadsFailsWithinTheLoadTimeout() {
        FrameBridge.loadTimeoutMillis = 500
        Environment.isAppInForeground = { true }
        try {
            val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
            var result: Result<Unit>? = null
            Environment.scope.launch { result = runCatching { ad.load() } }
            idle(800)
            assertEquals(500, (result!!.exceptionOrNull() as VideoAdException.CreativeFailed).code)
            assertEquals(VideoAd.InvalidationReason.CreativeFailed(500), ad.invalidationReason)
        } finally {
            FrameBridge.loadTimeoutMillis = 10_000
        }
    }

    @Test fun aLinearAdGivesItsBridgeTheVideosDurationAndAnOverlayNone() {
        val durations = mutableListOf<Long?>()
        VideoAd.makeBridge = { _, _, _, _, duration ->
            durations += duration
            throw android.util.AndroidRuntimeException("enough: the bridge was asked for")
        }
        val linear = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.linear())), Environment.now(), owner())
        val overlay = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay(duration = "8000"))), Environment.now(), owner())
        Environment.scope.launch { runCatching { linear.load() } }
        Environment.scope.launch { runCatching { overlay.load() } }
        idle()
        assertEquals(listOf(linear.durationMillis, null), durations)
        assertTrue("the fixture's linear ad has a duration", linear.durationMillis != null)
    }

    @Test fun aWebViewThatCannotBeMadeFailsTheLoadAsACreativeFailure() {
        VideoAd.makeBridge = { _, _, _, _, _ -> throw android.util.AndroidRuntimeException("no WebView package") }
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay(tracking = tracking("error")))), Environment.now(), owner())
        var result: Result<Unit>? = null
        Environment.scope.launch { result = runCatching { ad.load() } }
        idle()
        assertEquals(500, (result!!.exceptionOrNull() as VideoAdException.CreativeFailed).code)
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(500), ad.invalidationReason)
        assertEquals(listOf("error"), events)
        // The callback form hears it too, rather than the app's process hearing an uncaught exception.
        var heard: VideoAdException? = null
        ad.load(object : VideoAdsCallback<VideoAd> {
            override fun onSuccess(result: VideoAd) = fail("no WebView")

            override fun onError(error: VideoAdException) {
                heard = error
            }
        })
        idle()
        assertTrue(heard is VideoAdException.Invalidated)
    }

    @Test fun loadWithACallbackDeliversTheAdOrTheError() {
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
        var loadedAd: VideoAd? = null
        ad.load(object : VideoAdsCallback<VideoAd> {
            override fun onSuccess(result: VideoAd) {
                loadedAd = result
            }

            override fun onError(error: VideoAdException) = fail(error.message)
        })
        idle()
        page(ad, "ad.loaded")
        idle()
        assertSame(ad, loadedAd)
        ad.reject()
        var error: VideoAdException? = null
        ad.load(object : VideoAdsCallback<VideoAd> {
            override fun onSuccess(result: VideoAd) = fail("rejected")

            override fun onError(error: VideoAdException) {
                error.also { e -> this@VideoAdLifecycleTest.lastError = e }
            }
        })
        idle()
        error = lastError
        assertTrue(error is VideoAdException.Invalidated)
    }

    private var lastError: VideoAdException? = null

    @Test fun aLoadInFlightThrowsDestroyedWhenItsOwnerIsDestroyed() {
        val owner = owner()
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner)
        var result: Result<Unit>? = null
        Environment.scope.launch { result = runCatching { ad.load() } }
        idle()
        owner.destroy()
        idle()
        assertTrue(result!!.exceptionOrNull() is VideoAdException.Destroyed)
    }

    // endregion

    // region Linear

    /** Shows [ad] from a host Activity and brings its screen up to [resumed]. */
    private fun present(ad: VideoAd, resumed: Boolean = true): org.robolectric.android.controller.ActivityController<LinearAdActivity> {
        val host = activity()
        ad.show(host)
        val controller = Robolectric.buildActivity(LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).create().start()
        if (resumed) controller.resume()
        return controller
    }

    /** A player screen declared `launchMode="singleInstance"`. */
    class SingleInstanceActivity : android.app.Activity()

    @Test fun aLinearAdStartedFromASingleInstanceActivitySaysItOpensInAnotherTask() {
        val warnings = mutableListOf<String>()
        Logger.sink = { level, message -> if (level == Configuration.LogLevel.WARNING) warnings += message }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app.packageManager).addOrUpdateActivity(
            android.content.pm.ActivityInfo().apply {
                name = SingleInstanceActivity::class.java.name
                packageName = app.packageName
                launchMode = android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
            },
        )
        linear().show(activity())
        assertTrue("an ordinary Activity: nothing to say", warnings.none { "singleInstance" in it })
        Sdk.adOnScreen?.end(VideoAd.EndReason.DISMISSED)
        val host = Robolectric.buildActivity(SingleInstanceActivity::class.java).setup().get()
        val ad = linear()
        ad.show(host)
        assertTrue("$warnings", warnings.any { "singleInstance" in it })
        assertTrue("still shown: only a warning", ad.wasShown)
    }

    @Test fun aLinearAdKeepsItsScreenThroughEveryConfigurationChange() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val screen = present(ad)
        val first = screen.get()
        val changes = listOf<(android.content.res.Configuration) -> Unit>(
            { it.fontScale = 1.3f },
            { it.setLocale(java.util.Locale.FRENCH) },
            { it.densityDpi = 480 },
            { it.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE },
            { it.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL },
            { it.keyboard = android.content.res.Configuration.KEYBOARD_QWERTY },
            { it.navigation = android.content.res.Configuration.NAVIGATION_DPAD },
            { it.touchscreen = android.content.res.Configuration.TOUCHSCREEN_NOTOUCH },
            { it.mcc = 208 },
        )
        for (change in changes) {
            val config = android.content.res.Configuration(screen.get().resources.configuration).also(change)
            screen.configurationChange(config)
            idle()
            assertSame("the same screen, not a new one", first, screen.get())
            assertFalse("change ${changes.indexOf(change)}", first.isFinishing)
        }
        assertFalse("the ad plays on", ad.hasEnded)
        assertTrue(recorder.reasons.isEmpty())
    }

    @Test fun aLinearAdPlaysThroughWithEveryBeaconAndClosesFromItsEndCard() {
        val recorder = Recorder()
        val ad = linear(tracking = tracking("creativeView", "start", "firstQuartile", "midpoint", "thirdQuartile", "complete", "creativeView|endCard", "close", "impression"))
        ad.listener = recorder
        val screen = present(ad)
        assertEquals(listOf("creativeView"), events)
        page(ad, "video.start", """"playhead": 0""")
        assertEquals(listOf("impression"), recorder.heard)
        page(ad, "video.firstQuartile", """"playhead": 7500""")
        page(ad, "video.midpoint", """"playhead": 15000""")
        page(ad, "video.thirdQuartile", """"playhead": 22500""")
        page(ad, "video.complete", """"playhead": 30000""")
        assertTrue(ad.isCompleted)
        page(ad, "ad.close")
        assertTrue(ad.hasEnded)
        assertTrue("onAdEnded waits for the screen to go", recorder.reasons.isEmpty())
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.CLOSED), recorder.reasons)
        assertEquals(
            listOf("creativeView", "start", "impression", "firstQuartile", "midpoint", "thirdQuartile", "complete", "creativeView", "close"),
            events,
        )
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aLinearAdClosedBeforeTheEndIsSkipped() {
        val recorder = Recorder()
        val ad = linear(tracking = tracking("skip"))
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "ad.close")
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
        assertEquals(listOf("skip"), events)
        assertFalse(ad.isCompleted)
    }

    @Test fun theVideosSkipFiresTheCheckpointsItPassed() {
        val recorder = Recorder()
        val progress = """[{"event": "progress", "url": "https://t.test/b?e=p5", "offset": 5000},
            {"event": "progress", "url": "https://t.test/b?e=p10", "offset": 10000},
            {"event": "skip", "url": "https://t.test/b?e=skip"}]"""
        val ad = linear(tracking = progress)
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.progress", """"offset": 5000, "playhead": 5000""")
        page(ad, "video.skip", """"playhead": 12000""")
        screen.pause().stop().destroy()
        assertEquals(listOf("p5", "p10", "skip"), events)
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
    }

    @Test fun aLinearScreenTakenAwayInterruptsTheAd() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val screen = present(ad)
        screen.pause().stop().destroy() // not the ad's doing
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aLinearShowFromAFinishingActivityIsRefused() {
        val host = activity().apply { finish() }
        assertThrows<VideoAdException.PresenterUnavailable> { linear().show(host) }
    }

    @Test fun backDoesNothingOnTheLinearScreen() {
        val ad = linear()
        val screen = present(ad)
        screen.get().onBackPressedDispatcher.onBackPressed()
        assertFalse(ad.hasEnded)
        assertFalse(screen.get().isFinishing)
    }

    @Test fun aRestoredLinearScreenWithoutItsAdFinishesAtOnce() {
        val controller = Robolectric.buildActivity(LinearAdActivity::class.java, Intent()).create()
        assertTrue(controller.get().isFinishing)
    }

    // endregion
}
