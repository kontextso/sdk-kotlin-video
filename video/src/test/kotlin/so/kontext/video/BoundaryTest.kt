package so.kontext.video

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.content.ContentPlayback
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.measurement.ViewExposure
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.InlineAdView
import so.kontext.video.internal.screen.LinearScreen
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.media3.track
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where the SDK meets Android: the player, the views, the network and the wire contract. Shown ads here skip
 * the WebView load: their bridge is told `ad.loaded` directly.
 */
@RunWith(RobolectricTestRunner::class)
class BoundaryTest {
    private val owners = mutableListOf<VideoAds>()

    @Before fun setup() {
        // Every ads request names the viewer.
        so.kontext.video.internal.state.SdkState.current.recordUser(so.kontext.video.KontextVideo.User("test-user"))
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.assertionFailure = {}
        Environment.sendBeacon = { _, _ -> null }
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        SdkInitializer.configureForTesting(Configuration("test-token"))
    }

    @After fun teardown() {
        LinearScreen.showTimeoutMillis = 5_000
        LinearScreen.dismissalTimeoutMillis = 10_000
        owners.forEach { it.destroy() }
        SdkInitializer.resetForTesting()
        Environment.reset()
    }

    private fun owner() = VideoAds(Content("ep", "en")).also { owners += it }

    /** A container in a visible window: Robolectric's plain Activity reports its window GONE even after setup. */
    private fun container(): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = FrameLayout(activity)
        activity.setContentView(view)
        view.layout(0, 0, 320, 400)
        shadowOf(Looper.getMainLooper()).idle()
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return view
    }

    private fun loaded(payload: AdPayload, owner: VideoAds): VideoAd {
        val ad = VideoAd(payload, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, payload.id, payload.frameUrl, payload.creative == AdPayload.Creative.VIDEO)
        bridge.receive("""{"bidId":"${payload.id}","type":"ad.loaded"}""", Origin.of(payload.frameUrl), true)
        ad.loadedBridge = bridge
        ad.state.markLoaded()
        return ad
    }

    private fun overlay(owner: VideoAds, duration: String = "null") =
        loaded(AdPayload.parse(Fixtures.json(Fixtures.overlay(duration = duration, tracking = "[]"))), owner)

    /** A Media3 player that plays, and the listener the adapter registered on it. */
    private fun media3(): Pair<Player, () -> Player.Listener> {
        val player = mockk<Player>(relaxed = true)
        every { player.applicationLooper } returns Looper.getMainLooper()
        every { player.playWhenReady } returns true
        every { player.playbackState } returns Player.STATE_READY
        every { player.playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
        every { player.contentPosition } returns 0
        every { player.isPlayingAd } returns false
        val listener = slot<Player.Listener>()
        every { player.addListener(capture(listener)) } returns Unit
        return player to { listener.captured }
    }

    private fun events(event: Int) = Player.Events(FlagSet.Builder().add(event).build())

    /** A position in the content, or in the player's own ad of [adGroup]. */
    private fun position(millis: Long, adGroup: Int = C.INDEX_UNSET) =
        Player.PositionInfo(null, 0, null, null, 0, millis, millis, adGroup, if (adGroup == C.INDEX_UNSET) C.INDEX_UNSET else 0)

    @Test fun anotherMediaItemEndsTheOverlayWhileStillPlaying() {
        val (player, listener) = media3()
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner)
        ad.show(container())
        listener().onMediaItemTransition(MediaItem.fromUri("https://media.test/next.mp4"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        listener().onEvents(player, events(Player.EVENT_MEDIA_ITEM_TRANSITION))
        assertTrue(ad.hasEnded)
        assertNull(ScreenSlot.current)
    }

    @Test fun aPauseAndAResumeInOneEventBatchStillEndTheOverlay() {
        val (player, listener) = media3()
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner)
        ad.show(container())
        every { player.playWhenReady } returns true // Media3 reports the batch's final state in its getters
        listener().onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener().onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener().onEvents(player, events(Player.EVENT_PLAY_WHEN_READY_CHANGED))
        assertTrue("the pause is not lost to the batch", ad.hasEnded)
    }

    @Test fun aMedia3SeekCountsNothingTowardsTheOverlaysDuration() {
        var now = 0L
        var position = 0L
        Environment.now = { now }
        val (player, listener) = media3()
        every { player.contentPosition } answers { position }
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner, duration = "500")
        ad.show(container())
        // A jump that playback could explain (900 ms in a second): only the seek event tells it apart.
        now = 1000
        position = 900
        listener().onPositionDiscontinuity(position(0), position(900), Player.DISCONTINUITY_REASON_SEEK)
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertFalse(ad.hasEnded)
        assertEquals(0L, ad.contentClock!!.elapsedMillis)
    }

    @Test fun thePlayersOwnAdIsNotContentTheOverlayEndsAndThePlayheadIsTheContents() {
        val (player, listener) = media3()
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner)
        val container = container()
        ad.show(container)
        // A mid-roll of the player's own ads loader: the position inside it is the ad's, the content's stays.
        every { player.isPlayingAd } returns true
        every { player.currentPosition } returns 0
        every { player.contentPosition } returns 40_000
        listener().onPositionDiscontinuity(position(40_000), position(0, adGroup = 0), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertTrue("the overlay does not run over another ad", ad.hasEnded)
        assertEquals(40_000L, owner.playheadMillis)
        val next = overlay(owner)
        try {
            next.show(container)
            fail("shown over the player's own ad")
        } catch (_: VideoAdException.ContentPaused) {
        }
        // Back to the content.
        every { player.isPlayingAd } returns false
        listener().onPositionDiscontinuity(position(0, adGroup = 0), position(40_000), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        next.show(container)
        assertTrue(next.wasShown)
        next.dismiss()
    }

    @Test fun aPlayerOnAnotherLooperIsNotFollowedAndNothingThrows() {
        val (player, _) = media3()
        every { player.applicationLooper } returns mockk(relaxed = true)
        val owner = owner()
        owner.track(player)
        verify(exactly = 0) { player.addListener(any()) }
        assertFalse("not playing: no overlay over a player the SDK cannot read", owner.playback.isPlaying)
        assertEquals(0L, owner.playheadMillis)
    }

    @Test fun anEndedAdHoldsNoActivity() {
        val ad = overlay(owner())
        val container = container()
        ad.show(container)
        val bridge = ad.loadedBridge!!
        assertSame(container.context, bridge.contextWrapper.baseContext)
        ad.dismiss()
        assertNull(ad.loadedBridge)
        assertSame(Environment.context, bridge.contextWrapper.baseContext)
    }

    @Test fun anEndedLinearAdDropsItsShownAd() {
        val owner = owner()
        val ad = loaded(AdPayload.parse(Fixtures.json(Fixtures.linear())), owner)
        ad.show(container().context as Activity)
        assertTrue(ad.linearCallbacks != null)
        ad.end(VideoAd.EndReason.DESTROYED)
        assertNull(ad.loadedBridge)
        assertNull("it captures the bridge and its destroyed WebView", ad.linearCallbacks)
    }

    @Test fun aHostRemovedByTheContainersHookEndsTheAdWithoutAnImpression() {
        val ad = overlay(owner())
        var impressions = 0
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                impressions++
            }
        }
        val root = container()
        val hooking = object : FrameLayout(root.context) {
            override fun onViewAdded(child: View) {
                super.onViewAdded(child)
                if (child is InlineAdView) removeView(child)
            }
        }
        root.addView(hooking)
        hooking.layout(0, 0, 320, 400)
        ad.show(hooking)
        assertEquals(0, impressions)
        assertTrue(ad.hasEnded)
    }

    @Test fun aScaledViewThatIsFullyVisibleIsFullyExposed() {
        val container = container()
        val view = FrameLayout(container.context)
        container.addView(view)
        view.layout(100, 100, 300, 300)
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        val (share, visible) = ViewExposure.measure(view)
        assertEquals(1.0, share, 0.001)
        assertEquals("the visible part in the view's own coordinates", Rect(0, 0, 200, 200), visible)
    }

    @Test fun aClippedViewIsExposedByWhatShows() {
        val container = container()
        val view = FrameLayout(container.context)
        container.addView(view)
        view.layout(0, 300, 320, 500) // half below the 400 px container
        val (share, visible) = ViewExposure.measure(view)
        assertEquals(0.5, share, 0.01)
        assertEquals(Rect(0, 0, 320, 100), visible)
    }

    @Test fun transparencyMultipliesAlongTheAncestors() {
        val container = container()
        var parent: android.view.ViewGroup = container
        repeat(3) {
            val layer = FrameLayout(container.context).apply { alpha = 0.1f }
            parent.addView(layer)
            layer.layout(0, 0, 320, 400)
            parent = layer
        }
        val view = FrameLayout(container.context)
        parent.addView(view)
        view.layout(0, 0, 320, 400)
        // 0.1 × 0.1 × 0.1 = 0.001: below 1 %, though no single ancestor is.
        assertEquals("not measured as seen", 0.0, ViewExposure.measure(view).share, 0.0)
        assertFalse("not eligible to show", ViewExposure.canBeSeen(view))
        // Two layers at 0.2 make 0.04: seen.
        (view.parent as View).alpha = 1f
        (view.parent!!.parent as View).alpha = 0.2f
        (view.parent!!.parent!!.parent as View).alpha = 0.2f
        assertEquals(1.0, ViewExposure.measure(view).share, 0.001)
        assertTrue(ViewExposure.canBeSeen(view))
    }

    @Test fun aParentsPaddingClipsTheCreative() {
        val container = container()
        val padded = FrameLayout(container.context).apply {
            setPadding(60, 60, 60, 60)
            clipChildren = false // padding clips on its own, as Android draws it
        }
        container.addView(padded)
        padded.layout(0, 0, 200, 200)
        val view = FrameLayout(container.context)
        padded.addView(view)
        view.layout(0, 0, 200, 200)
        val (share, visible) = ViewExposure.measure(view)
        assertEquals("80×80 of 200×200", 0.16, share, 0.001)
        assertEquals(Rect(60, 60, 140, 140), visible)
    }

    @Test fun aViewInAScrolledParentIsMeasuredWhereItShows() {
        // container 320×400 └─ a scrolling parent at (0, 100), 320×300 └─ the ad 320×80 at (40, 300)
        val container = container()
        val scroller = FrameLayout(container.context)
        container.addView(scroller)
        scroller.layout(0, 100, 320, 400)
        val view = FrameLayout(container.context)
        scroller.addView(view)
        view.layout(40, 300, 360, 380)
        // Scrolled down by 200: the ad sits at y 100–180 in the parent, whole in height; the parent cuts the
        // 40 px it sticks out on the right.
        scroller.scrollTo(0, 200)
        val (share, visible) = ViewExposure.measure(view)
        assertEquals("280×80 of 320×80", 0.875, share, 0.001)
        assertEquals(Rect(0, 0, 280, 80), visible)
        // Scrolled by 350: the ad sits at y −50 to 30, only its bottom 30 rows show.
        scroller.scrollTo(0, 350)
        val (scrolledShare, scrolledVisible) = ViewExposure.measure(view)
        assertEquals("280×30 of 320×80", 0.328, scrolledShare, 0.001)
        assertEquals(Rect(0, 50, 280, 80), scrolledVisible)
    }

    @Test fun aParentThatDoesNotClipLetsTheAdShowBeyondIt() {
        // The ad, 120×80 at x 100 in a 160×200 parent, sticks out 60 px; the 320×400 container around both clips.
        val container = container()
        val parent = FrameLayout(container.context).apply { clipChildren = false }
        container.addView(parent)
        parent.layout(0, 0, 160, 200)
        val view = FrameLayout(container.context)
        parent.addView(view)
        view.layout(100, 0, 220, 80)
        assertEquals("all of it inside the container", 1.0, ViewExposure.measure(view).share, 0.001)
        parent.clipChildren = true
        assertEquals("cut at the parent's edge: 60 of 120", 0.5, ViewExposure.measure(view).share, 0.001)
    }

    @Test fun paddingDoesNotClipWithoutClipToPadding() {
        val container = container()
        val padded = FrameLayout(container.context).apply {
            setPadding(60, 60, 60, 60)
            clipChildren = false
            clipToPadding = false
        }
        container.addView(padded)
        padded.layout(0, 0, 200, 200)
        val view = FrameLayout(container.context)
        padded.addView(view)
        view.layout(0, 0, 200, 200)
        val (share, visible) = ViewExposure.measure(view)
        assertEquals("the padding is only spacing", 1.0, share, 0.001)
        assertEquals(Rect(0, 0, 200, 200), visible)
    }

    @Test fun paddingThatLeavesNoRoomHidesTheAd() {
        val container = container()
        val padded = FrameLayout(container.context).apply {
            setPadding(100, 0, 100, 0) // 200 px of padding in a 200 px wide parent
            clipChildren = false
        }
        container.addView(padded)
        padded.layout(0, 0, 200, 200)
        val view = FrameLayout(container.context)
        padded.addView(view)
        view.layout(0, 0, 200, 200)
        val (share, visible) = ViewExposure.measure(view)
        assertEquals(0.0, share, 0.0)
        assertTrue("nothing to show", visible.isEmpty)
    }

    @Test fun aLinearScreenThatNeverComesToTheFrontEndsTheAd() {
        LinearScreen.showTimeoutMillis = 1_000
        LinearScreen.dismissalTimeoutMillis = 300 // the failed screen is finished; Robolectric never destroys it
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ad = loaded(AdPayload.parse(Fixtures.json(Fixtures.linear())), owner())
        val reasons = mutableListOf<VideoAd.EndReason>()
        ad.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                reasons += reason
            }
        }
        ad.show(host)
        val started = shadowOf(host).nextStartedActivity
        // The screen is created and started (it attaches the page) but never resumed.
        val screen = Robolectric.buildActivity(so.kontext.video.internal.screen.LinearAdActivity::class.java, started).create().start()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))
        assertTrue(ad.hasEnded)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertNull(ScreenSlot.current)
        assertEquals(listOf(VideoAd.EndReason.FAILED), reasons)
        // The screen comes to the front after all, then goes: the ad ended once and stays ended.
        screen.resume().pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        assertEquals(listOf(VideoAd.EndReason.FAILED), reasons)
        assertNull(ScreenSlot.current)
    }

    @Test fun aLinearScreenThatNeverClosesIsReportedAtTheCapAndOnAdEndedComesOnce() {
        LinearScreen.dismissalTimeoutMillis = 500
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = owner()
        val ad = loaded(AdPayload.parse(Fixtures.json(Fixtures.linear())), owner)
        val reasons = mutableListOf<VideoAd.EndReason>()
        ad.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                reasons += reason
            }
        }
        ad.show(host)
        // Created and resumed, so on screen; it never stops or is destroyed after finish().
        val screen = Robolectric.buildActivity(so.kontext.video.internal.screen.LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).setup()
        ad.end(VideoAd.EndReason.CLOSED)
        assertTrue(screen.get().isFinishing)
        assertTrue("waits for the screen to close", reasons.isEmpty())
        // The screen is still taken while it closes: another ad cannot show over it.
        val next = loaded(AdPayload.parse(Fixtures.json(Fixtures.linear())), owner)
        assertThrows(VideoAdException.AnotherAdShowing::class.java) { next.show(host) }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        assertEquals("delivered at the cap", listOf(VideoAd.EndReason.CLOSED), reasons)
        assertEquals(listOf("ad.dismissal_timed_out"), so.kontext.video.internal.diagnostics.ErrorReporter.bufferedCodes)
        // The screen goes after all: nothing more.
        screen.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_000))
        assertEquals("once", listOf(VideoAd.EndReason.CLOSED), reasons)
        assertEquals("reported once", 1, so.kontext.video.internal.diagnostics.ErrorReporter.bufferedCodes.size)
        assertNull(ScreenSlot.current)
        next.show(host)
        assertTrue(next.wasShown)
    }

    @Test fun aLinearScreenThatStopsAsItFinishesDeliversOnAdEndedThenWithoutWaitingForItsDestroy() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ad = loaded(AdPayload.parse(Fixtures.json(Fixtures.linear())), owner())
        val reasons = mutableListOf<VideoAd.EndReason>()
        ad.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                reasons += reason
            }
        }
        ad.show(host)
        val screen = Robolectric.buildActivity(so.kontext.video.internal.screen.LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).setup()
        ad.end(VideoAd.EndReason.SKIPPED)
        assertTrue(reasons.isEmpty())
        screen.pause()
        assertTrue("not at the pause", reasons.isEmpty())
        screen.stop() // the system may hold the destroy back for seconds; the stop is the screen gone
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), reasons)
        assertNull(ScreenSlot.current)
        screen.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LinearScreen.dismissalTimeoutMillis + 1_000))
        assertEquals("once, and no watchdog report", listOf(VideoAd.EndReason.SKIPPED), reasons)
        assertTrue("ad.dismissal_timed_out" !in so.kontext.video.internal.diagnostics.ErrorReporter.bufferedCodes)
    }

    @Test fun aPhoneIsAPhoneOnTheWire() {
        assertEquals("phone", DeviceInfo.hardware(Environment.context!!)["type"]!!.jsonPrimitive.content)
    }

    @Test fun theAdvertisingIdIsAnAaid() = runBlocking {
        Environment.advertisingId = { "00000000-1111-2222-3333-444444444444" }
        val request = AdsRequest.make(Environment.context!!, "token", Content("ep", "en"), null, null, 0, "session")
        val ifa = request.body["ifa"]!!.jsonObject
        assertEquals("aaid", ifa["type"]!!.jsonPrimitive.content)
        assertEquals("00000000-1111-2222-3333-444444444444", ifa["id"]!!.jsonPrimitive.content)
    }

    @Test fun thePrivacyFieldsAreReadOnceTheUserAgentIsIn() {
        val userAgent = kotlinx.coroutines.CompletableDeferred<String>()
        Environment.userAgent = { userAgent.await() }
        var adId = "00000000-0000-0000-0000-000000000001"
        Environment.advertisingId = { adId }
        KontextVideo.user = User("before")
        KontextVideo.regulatory = Regulatory { gdpr = false }
        var body: kotlinx.serialization.json.JsonObject? = null
        Environment.scope.launch {
            body = AdsRequest.make(Environment.context!!, "token", Content("ep", "en"), null, null, 0, "session").body
        }
        shadowOf(Looper.getMainLooper()).idle()
        // While the user agent is read: the viewer resets the ad id, the consent changes, another user signs in.
        adId = "00000000-0000-0000-0000-000000000002"
        KontextVideo.user = User("after")
        KontextVideo.regulatory = Regulatory {
            gdpr = true
            gdprConsent = "consent-string"
        }
        userAgent.complete("test-agent")
        // The stored consent is read off the main thread: the request is built once that read is back.
        val deadline = System.currentTimeMillis() + 5_000
        while (body == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val sent = body!!
        assertEquals("00000000-0000-0000-0000-000000000002", sent["ifa"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("after", sent["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("true", sent["regulatory"]!!.jsonObject["gdpr"]!!.jsonPrimitive.content)
        assertEquals("consent-string", sent["regulatory"]!!.jsonObject["gdprConsent"]!!.jsonPrimitive.content)
        KontextVideo.user = null
        KontextVideo.regulatory = null
    }

    @Test fun aTricklingAnswerHitsTheWholeCallDeadline() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"answer":123456789}""").throttleBody(1, 40, TimeUnit.MILLISECONDS))
            // The body trickles for about a second; a whole-call deadline of 150 ms ends the call with an error first.
            try {
                ApiClient(server.url("/").toString(), timeoutMillis = 150).post("config", buildJsonObject {})
                fail("completed despite the 150 ms deadline")
            } catch (_: ApiException.Transport) {
            }
        } finally {
            server.shutdown()
        }
    }

    /** Runs the exchange on its own thread and says when that thread is done with it. */
    private class WatchedExecutor : java.util.concurrent.Executor {
        val finished = CountDownLatch(1)

        @Volatile var worker: Thread? = null

        override fun execute(command: Runnable) {
            worker = Thread {
                try {
                    command.run()
                } finally {
                    finished.countDown()
                }
            }.also { it.start() }
        }

        /**
         * Waits until the worker reads the answer: HttpTransport says so just before the read, and a moment
         * later it is blocked in it. The server has the request once the body is sent, a moment before the read
         * starts; a disconnect in that moment lets the connection open again and wait. No JDK internals: the
         * signal is the SDK's own.
         */
        fun awaitReading(reading: CountDownLatch) {
            if (!reading.await(5, TimeUnit.SECONDS)) fail("the worker never read the answer")
            Thread.sleep(SETTLE_MILLIS)
        }

        private companion object {
            /** From the signal to the blocked read: the next line of the worker's, generous for a slow runner. */
            const val SETTLE_MILLIS = 200L
        }
    }

    @Test fun aCancelledRequestEndsAndFreesItsWorkerWhileTheServerStillHoldsIt() = runBlocking {
        val server = MockWebServer()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        val executor = WatchedExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reading = CountDownLatch(1)
        so.kontext.video.internal.network.HttpTransport.onReading = { reading.countDown() }
        try {
            val job = scope.async { ApiClient(server.url("/").toString(), 10_000, executor).post("config", buildJsonObject {}) }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            executor.awaitReading(reading)
            job.cancel()
            job.join() // the caller is gone while the server still holds the request
            assertTrue("the disconnect ended the blocked read", executor.finished.await(5, TimeUnit.SECONDS))
            assertEquals("the server has not answered yet", 1L, release.count)
        } finally {
            so.kontext.video.internal.network.HttpTransport.onReading = null
            release.countDown()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun aRequestCancelledWhileQueuedSendsNothing() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{}"))
        val queued = java.util.ArrayDeque<Runnable>()
        try {
            val call = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                ApiClient(server.url("/").toString(), 10_000, queued::add).post("video/ads", buildJsonObject {})
            }
            assertEquals("the I/O work is queued", 1, queued.size)
            call.cancel()
            call.join()
            queued.removeFirst().run() // the worker gets its turn after the caller left
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun aHostHiddenByTheContainersHookEndsTheAdWithoutAnImpression() {
        val ad = overlay(owner())
        var impressions = 0
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                impressions++
            }
        }
        val root = container()
        val hooking = object : FrameLayout(root.context) {
            override fun onViewAdded(child: View) {
                super.onViewAdded(child)
                if (child is InlineAdView) child.visibility = View.GONE
            }
        }
        root.addView(hooking)
        hooking.layout(0, 0, 320, 400)
        ad.show(hooking)
        assertEquals(0, impressions)
        assertTrue(ad.hasEnded)
    }

    @Test fun anEndedOverlayDoesNotKeepItsDestroyedOwnerAlive() {
        val (ad, owner) = endedOverlayOfADestroyedOwner()
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            System.gc()
            System.runFinalization()
        }
        assertNull("the ad was kept; its owner should not be", owner.get())
        assertTrue(ad.hasEnded)
    }

    private fun endedOverlayOfADestroyedOwner(): Pair<VideoAd, java.lang.ref.WeakReference<VideoAds>> {
        val owner = VideoAds(Content("ep", "en"))
        owner.track(object : ContentPlayer {
            override val isPlaying = true
            override val currentPositionMillis = 0L
        })
        val ad = overlay(owner, duration = "6000")
        ad.show(container())
        ad.dismiss()
        owner.destroy()
        return ad to java.lang.ref.WeakReference(owner)
    }

    @Test fun aRecyclerViewContainerIsRefusedAndNeverCrashesItsLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = androidx.recyclerview.widget.RecyclerView(activity)
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        list.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(android.widget.FrameLayout(parent.context).apply { minimumHeight = 100 }) {}

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) = Unit

            override fun getItemCount() = 3
        }
        activity.setContentView(list)
        list.layout(0, 0, 320, 400)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.util.ReflectionHelpers.setField(org.robolectric.util.ReflectionHelpers.getField<Any>(list, "mAttachInfo"), "mWindowVisibility", android.view.View.VISIBLE)
        val ad = overlay(owner())
        val thrown = runCatching { ad.show(list) }.exceptionOrNull()
        // Whatever show said, the list's next layout must not crash the app.
        list.requestLayout()
        list.measure(android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 320, 400)
        assertTrue("$thrown", thrown is VideoAdException.CannotShowNow)
        assertTrue("it stays ready", ad.isReady)
    }
}
