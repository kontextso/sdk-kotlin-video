package so.kontext.video

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException.RequestFailure
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.diagnostics.EventHistory
import so.kontext.video.internal.network.PendingBeacons
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The ad server's `/v2` contract as this release follows it, end to end against a local server: the required
 * user, the wait for outcome beacons, `pauseFullscreen`, and the debug events of these moments.
 */
@RunWith(RobolectricTestRunner::class)
class ContractV2Test {
    private val server = MockWebServer()
    private val requests = ConcurrentLinkedQueue<Pair<String, String>>()
    private val beacons = mutableListOf<String>()

    /** What a beacon's job is, by its URL: `null` (done at once) unless a test holds one back. */
    private var beaconJob: (String) -> Job? = { null }

    @Volatile private var adsAnswer: MockResponse = MockResponse().setBody(Fixtures.ads().toString())

    @Volatile private var configAnswer: MockResponse = MockResponse().setBody("""{"enabled": true}""")

    private val pauseWithNotUsed = Fixtures.pause().replace("\"tracking\": []", """"tracking": [{"event": "notUsed", "url": "https://t.test/b?e=notUsed&r=[KONTEXT_REASON]"}]""")

    @Before fun start() {
        SdkState.current.recordUser(KontextVideo.User("test-user"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += path to request.body.readUtf8()
                return when (path) {
                    "/v2/config" -> configAnswer
                    "/v2/video/ads" -> adsAnswer
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        Environment.assertionFailure = {}
        Environment.sendBeacon = { url, _ ->
            beacons += url
            beaconJob(url)
        }
    }

    @After fun stop() {
        SdkInitializer.resetForTesting()
        Environment.reset()
        server.shutdown()
    }

    private fun initialize(): KontextVideo.State {
        var state: KontextVideo.State? = null
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("test-token") { adServerUrl = server.url("/").toString() }) { state = it }
        await { state != null }
        return state!!
    }

    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    /** Runs the main looper for [millis] of real time, the clock standing still. */
    private fun settle(millis: Long = 300) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun startFormat(ads: VideoAds, format: AdFormat): () -> Result<VideoAd>? {
        var result: Result<VideoAd>? = null
        Environment.scope.launch {
            result = try {
                Result.success(ads.format(format))
            } catch (error: VideoAdException) {
                Result.failure(error)
            }
        }
        return { result }
    }

    private fun format(ads: VideoAds, format: AdFormat): Result<VideoAd> {
        val result = startFormat(ads, format)
        await { result() != null }
        return result()!!
    }

    private fun adsBodies() = requests.filter { it.first == "/v2/video/ads" }.map { Json.parseToJsonElement(it.second).jsonObject }

    private fun events(name: String): List<JsonObject> = EventHistory.recent().filter { it["name"]?.jsonPrimitive?.content == name }

    private fun JsonObject.property(key: String) = this["properties"]?.jsonObject?.get(key)?.jsonPrimitive?.content

    private fun JsonObject.level() = this["level"]?.jsonPrimitive?.content

    // region The user

    @Test fun withoutAUserFormatSendsNothingAndFailsWithMissingUser() {
        initialize()
        SdkState.current.recordUser(null)
        val assertions = mutableListOf<String>()
        Environment.assertionFailure = { assertions += it }
        val ads = VideoAds(Content("ep", "en"))
        val error = format(ads, AdFormat.PAUSE).exceptionOrNull()
        assertEquals(RequestFailure.MissingUser, (error as VideoAdException.Request).failure)
        assertEquals("missingUser", RequestFailure.MissingUser.toString())
        assertTrue("no ads request left", adsBodies().isEmpty())
        assertTrue(assertions.single().contains("KontextVideo.user"))
        val failed = events("ads.requestFailed").single()
        assertEquals("missingUser", failed.property("failure"))
        assertEquals("warning", failed.level())
        // Set, the next format asks.
        KontextVideo.user = KontextVideo.User("viewer-1")
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        assertEquals("ad-2", format(ads, AdFormat.PAUSE).getOrThrow().id)
        assertEquals("viewer-1", adsBodies().single()["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        ads.destroy()
    }

    @Test fun missingUserIsThrownEvenWithAValidHeldAd() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ads = VideoAds(Content("ep", "en"))
        val held = format(ads, AdFormat.PAUSE).getOrThrow()
        SdkState.current.recordUser(null)
        val error = format(ads, AdFormat.PAUSE).exceptionOrNull()
        assertEquals("an integration error is never hidden by the held ad", RequestFailure.MissingUser, (error as VideoAdException.Request).failure)
        assertNull("the held ad stays held", held.invalidationReason)
        assertEquals(1, adsBodies().size)
        ads.destroy()
    }

    @Test fun anEmptyOrTooLongUserIdIsNoUser() {
        initialize()
        SdkState.current.recordUser(KontextVideo.User(""))
        val ads = VideoAds(Content("ep", "en"))
        assertEquals(RequestFailure.MissingUser, (format(ads, AdFormat.OVERLAY).exceptionOrNull() as VideoAdException.Request).failure)
        assertTrue(adsBodies().isEmpty())
        // Longer than the server's 256 characters: no user either.
        SdkState.current.recordUser(KontextVideo.User("u".repeat(257)))
        assertEquals(RequestFailure.MissingUser, (format(ads, AdFormat.OVERLAY).exceptionOrNull() as VideoAdException.Request).failure)
        assertTrue(adsBodies().isEmpty())
        SdkState.current.recordUser(KontextVideo.User("u".repeat(256)))
        format(ads, AdFormat.OVERLAY)
        assertEquals("256 is the limit, inclusive", 256, adsBodies().single()["user"]!!.jsonObject["id"]!!.jsonPrimitive.content.length)
        ads.destroy()
    }

    // endregion

    // region The wait for outcome beacons

    @Test fun aRejectsNotUsedIsHeardBeforeTheNextAdsRequest() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", pauseWithNotUsed)).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.PAUSE).getOrThrow()
        val notUsed = Job()
        beaconJob = { url -> if ("e=notUsed" in url) notUsed else null }
        ad.reject()
        assertEquals(1, beacons.count { "e=notUsed" in it })
        val next = startFormat(ads, AdFormat.PAUSE)
        settle()
        assertEquals("the request waits for the notUsed beacon", 1, adsBodies().size)
        notUsed.complete()
        await { next() != null }
        assertEquals(2, adsBodies().size)
        val notUsedEvent = events("ad.notUsed").single()
        assertEquals("notUsed", notUsedEvent.property("reason"))
        assertEquals("info", notUsedEvent.level() ?: "info")
        ads.destroy()
    }

    @Test fun aBeaconThatNeverAnswersHoldsTheRequestBackTwoSecondsAtMost() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", pauseWithNotUsed)).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.PAUSE).getOrThrow()
        val never = Job()
        beaconJob = { never }
        ad.reject(VideoAd.RejectReason.Outbid(3.0, "net"))
        val next = startFormat(ads, AdFormat.PAUSE)
        settle()
        assertEquals(1, adsBodies().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PendingBeacons.WAIT_MILLIS - 100))
        settle(100)
        assertEquals("still waiting just before the cap", 1, adsBodies().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        await { next() != null }
        assertEquals("past the cap the request goes", 2, adsBodies().size)
        assertTrue(never.isActive)
        assertEquals("outbid", events("ad.notUsed").single().property("reason"))
        ads.destroy()
    }

    @Test fun theUserAndConsentAreReadAfterTheBeaconWait() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", pauseWithNotUsed)).toString())
        KontextVideo.user = KontextVideo.User("viewer-1") { email = "v@example.com" }
        val ads = VideoAds(Content("ep", "en"))
        val notUsed = Job()
        beaconJob = { url -> if ("e=notUsed" in url) notUsed else null }
        format(ads, AdFormat.PAUSE).getOrThrow().reject()
        val next = startFormat(ads, AdFormat.PAUSE)
        settle()
        // While the request waits: COPPA turns on.
        KontextVideo.regulatory = KontextVideo.Regulatory { coppa = true }
        notUsed.complete()
        await { next() != null }
        val sent = adsBodies().last()["user"]!!.jsonObject
        assertEquals("the id stays, the email goes: read after the wait", setOf("id"), sent.keys)
        // And a user cleared during the wait: nothing leaves.
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", pauseWithNotUsed.replace("ad-2", "ad-9"))).toString())
        KontextVideo.regulatory = null
        val again = Job()
        beaconJob = { url -> if ("e=notUsed" in url) again else null }
        format(ads, AdFormat.PAUSE).getOrThrow().reject()
        val count = adsBodies().size
        val last = startFormat(ads, AdFormat.PAUSE)
        settle()
        SdkState.current.recordUser(null)
        again.complete()
        await { last() != null }
        assertEquals(RequestFailure.MissingUser, (last()!!.exceptionOrNull() as VideoAdException.Request).failure)
        assertEquals("no request without a user", count, adsBodies().size)
        ads.destroy()
    }

    @Test fun aBeaconStillOnTheWireAtTheCapIsWaitedForAgainByTheNextRequest() {
        val pending = PendingBeacons()
        val slow = Job()
        pending.add(slow)
        var first = false
        Environment.scope.launch {
            pending.await()
            first = true
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PendingBeacons.WAIT_MILLIS + 1))
        assertTrue("the first request goes at the cap", first)
        var second = false
        Environment.scope.launch {
            pending.await()
            second = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the next one waits for the same beacon", second)
        slow.complete()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second)
    }

    @Test fun otherBeaconsAreNotWaitedFor() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ads = VideoAds(Content("ep", "en"))
        format(ads, AdFormat.PAUSE).getOrThrow()
        val pending = PendingBeacons()
        pending.add(Job().apply { complete() })
        pending.add(null)
        // Nothing on the wire: no wait at all.
        var done = false
        Environment.scope.launch {
            pending.await()
            done = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done)
        ads.destroy()
    }

    // endregion

    // region pauseFullscreen

    @Test fun pauseFullscreenIsAFormatOfItsOwn() {
        initialize()
        adsAnswer = MockResponse().setBody(
            Fixtures.ads(Fixtures.entry("pause", Fixtures.pause()), Fixtures.entry("pauseFullscreen", Fixtures.pauseFullscreen())).toString(),
        )
        val ads = VideoAds(Content("ep", "en"), section = "player")
        val fullscreen = format(ads, AdFormat.PAUSE_FULLSCREEN).getOrThrow()
        assertEquals("ad-4", fullscreen.id)
        assertEquals(AdFormat.PAUSE_FULLSCREEN, fullscreen.format)
        assertNull(fullscreen.durationMillis)
        assertEquals("the same answer holds both", "ad-2", format(ads, AdFormat.PAUSE).getOrThrow().id)
        val request = events("ads.request").first()
        assertEquals("player", request.property("section"))
        assertNull("no held count any more", request.property("held"))
        ads.destroy()
    }

    // endregion

    // region Debug events

    @Test fun aFailedRequestSaysItsKindAndStatus() {
        initialize()
        adsAnswer = MockResponse().setResponseCode(503)
        val ads = VideoAds(Content("ep", "en"))
        assertEquals(RequestFailure.Server(503), (format(ads, AdFormat.OVERLAY).exceptionOrNull() as VideoAdException.Request).failure)
        val failed = events("ads.requestFailed").single()
        assertEquals("server", failed.property("failure"))
        assertEquals(503, failed["properties"]!!.jsonObject["httpStatus"]!!.jsonPrimitive.int)
        assertEquals("warning", failed.level())
        ads.destroy()
    }

    @Test fun aFailedConfigIsADebugEvent() {
        configAnswer = MockResponse().setResponseCode(500)
        assertEquals(KontextVideo.State.Failed, initialize())
        val failed = events("config.failed").single()
        assertEquals("server", failed.property("failure"))
        assertEquals("500", failed.property("httpStatus"))
        assertEquals("warning", failed.level())
    }

    @Test fun anAdThatExpiresIsADebugEvent() {
        initialize()
        var now = 1_000_000L
        Environment.now = { now }
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.PAUSE).getOrThrow()
        now += 600_001
        ad.reject()
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
        val expired = events("ad.expired").single()
        assertEquals("pause", expired.property("format"))
        assertEquals(ad.id, expired["bidId"]?.jsonPrimitive?.content)
        assertTrue("an expired ad gets no notUsed", events("ad.notUsed").isEmpty())
        assertNotNull(events("ad.invalidated").singleOrNull())
        ads.destroy()
    }

    // endregion
}
