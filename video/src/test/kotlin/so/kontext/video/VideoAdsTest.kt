package so.kontext.video

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.support.Environment
import java.util.concurrent.ConcurrentLinkedQueue

/** `KontextVideo` and `VideoAds` against a local HTTP server: the SDK's own requests run unchanged. */
@RunWith(RobolectricTestRunner::class)
class VideoAdsTest {
    private val server = MockWebServer()
    private val requests = ConcurrentLinkedQueue<Pair<String, String>>()
    private val beacons = mutableListOf<String>()

    /** What `/v2/video/ads` answers next; `/v2/config` is enabled unless a test says otherwise. */
    @Volatile private var adsAnswer: MockResponse = MockResponse().setBody(Fixtures.ads().toString())

    @Volatile private var configAnswer: MockResponse = MockResponse().setBody("""{"enabled": true}""")

    @Before fun start() {
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
        Environment.sendBeacon = { url, _ -> beacons += url }
    }

    @After fun stop() {
        Sdk.resetForTesting()
        Environment.reset()
        server.shutdown()
    }

    private fun initialize(): KontextVideo.State {
        var state: KontextVideo.State? = null
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("test-token") { adServerUrl = server.url("/").toString() }) { state = it }
        await { state != null }
        return state!!
    }

    /** Runs the main looper until [done], with real time for the HTTP threads. */
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    /** `format` as a result: the ad, or the exception it threw. */
    private fun format(ads: VideoAds, format: AdFormat): Result<VideoAd> {
        var result: Result<VideoAd>? = null
        Environment.scope.launch {
            result = try {
                Result.success(ads.format(format))
            } catch (error: VideoAdException) {
                Result.failure(error)
            }
        }
        await { result != null }
        return result!!
    }

    private fun adsBodies() = requests.filter { it.first == "/v2/video/ads" }.map { Json.parseToJsonElement(it.second).jsonObject }

    @Test fun initializeAsksConfigAndEnables() {
        assertEquals(KontextVideo.State.Enabled, initialize())
        val body = Json.parseToJsonElement(requests.first { it.first == "/v2/config" }.second).jsonObject
        assertEquals("test-token", body["publisherToken"]?.jsonPrimitive?.content)
        assertEquals("sdk-kotlin-video", body["sdk"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test fun theServerCanSwitchTheSdkOff() {
        configAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "paused"}""")
        assertEquals(KontextVideo.State.Disabled("paused"), initialize())
        val error = format(VideoAds(Content("ep", "en")), AdFormat.PAUSE).exceptionOrNull()
        assertTrue(error is VideoAdException.Disabled)
    }

    @Test fun aFailedConfigIsTriedAgainByTheNextFormat() {
        configAnswer = MockResponse().setResponseCode(500)
        assertEquals(KontextVideo.State.Failed, initialize())
        configAnswer = MockResponse().setBody("""{"enabled": true}""")
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        assertEquals("ad-2", format(VideoAds(Content("ep", "en")), AdFormat.PAUSE).getOrThrow().id)
        assertEquals(KontextVideo.State.Enabled, KontextVideo.state)
    }

    @Test fun formatReturnsTheAdAndListsItAsHeldNextTime() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ads = VideoAds(Content("ep", "en") { title = "Episode" }, mapOf("stage" to "first_episode"))
        val ad = format(ads, AdFormat.PAUSE).getOrThrow()
        assertEquals(VideoAd.Price(5.5, "EUR", VideoAd.Price.Precision.ESTIMATED), ad.price)
        val again = format(ads, AdFormat.PAUSE).getOrThrow()
        assertSame("the same id keeps the same ad", ad, again)
        val bodies = adsBodies()
        assertEquals("first_episode", bodies[0]["labels"]?.jsonObject?.get("stage")?.jsonPrimitive?.content)
        assertEquals("ep", bodies[0]["video"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertTrue(bodies[0]["held"]!!.jsonArray.isEmpty())
        assertEquals("ad-2", bodies[1]["held"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(bodies[1]["requestId"]?.jsonPrimitive?.content, ads.lastRequestId)
        ads.destroy()
    }

    @Test fun aNoFillSaysWhy() {
        initialize()
        adsAnswer = MockResponse().setBody(
            Fixtures.ads(
                Fixtures.entry("pause", noFill = "frequencyCap"),
                Fixtures.entry("overlay", noFill = "formatNotEnabled"),
                Fixtures.entry("linear", noFill = "somethingNew"),
            ).toString(),
        )
        val ads = VideoAds(Content("ep", "en"))
        val error = format(ads, AdFormat.PAUSE).exceptionOrNull()
        assertEquals(NoFillReason.FrequencyCap, (error as VideoAdException.NoFill).reason)
        assertEquals(NoFillReason.FormatNotEnabled, (format(ads, AdFormat.OVERLAY).exceptionOrNull() as VideoAdException.NoFill).reason)
        assertEquals("a reason this SDK does not know yet", NoFillReason.Other("somethingNew"), (format(ads, AdFormat.LINEAR).exceptionOrNull() as VideoAdException.NoFill).reason)
        ads.destroy()
    }

    @Test fun aFailedRequestReturnsTheHeldAdAndOtherwiseThrows() {
        initialize()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        val ads = VideoAds(Content("ep", "en"))
        val held = format(ads, AdFormat.PAUSE).getOrThrow()
        adsAnswer = MockResponse().setResponseCode(503)
        assertSame(held, format(ads, AdFormat.PAUSE).getOrThrow())
        val error = format(ads, AdFormat.OVERLAY).exceptionOrNull()
        assertTrue(error is VideoAdException.Request)
        val now = Environment.now()
        Environment.now = { now + 600_001 }
        assertTrue("an expired held ad is not a fallback", format(ads, AdFormat.PAUSE).exceptionOrNull() is VideoAdException.Request)
        ads.destroy()
    }

    @Test fun aMalformedAdCostsOnlyItsOwnFormatAndIsReportedOnce() {
        configAnswer = MockResponse().setBody("""{"enabled": true, "reportErrors": true}""")
        initialize()
        val broken = Fixtures.overlay().replace("\"precision\": \"exact\"", "\"precision\": \"floor\"")
        adsAnswer = MockResponse().setBody(
            Fixtures.ads(Fixtures.entry("overlay", broken), Fixtures.entry("pause", Fixtures.pause()), Fixtures.entry("menu")).toString(),
        )
        val ads = VideoAds(Content("ep", "en"))
        assertEquals("the other format's ad", "ad-2", format(ads, AdFormat.PAUSE).getOrThrow().id)
        val requestId = ads.lastRequestId
        val error = format(ads, AdFormat.OVERLAY).exceptionOrNull()
        assertEquals(NoFillReason.Unknown, (error as VideoAdException.NoFill).reason)
        // The reports leave after a short delay on the main looper: its clock moves with the waiting.
        await {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            requests.any { it.first == "/v2/error" }
        }
        val reports = requests.filter { it.first == "/v2/error" }.flatMap { Json.parseToJsonElement(it.second).jsonObject["errors"]!!.jsonArray }
        val report = reports.map { it.jsonObject }.single { it["requestId"]?.jsonPrimitive?.content == requestId }
        assertEquals("ads.decode_failed", report["code"]?.jsonPrimitive?.content)
        assertEquals("overlay", report["detail"]?.jsonPrimitive?.content)
        assertEquals("no_ad", report["recovery"]?.jsonPrimitive?.content)
        ads.destroy()
    }

    @Test fun anAdsResponseCanSwitchTheSdkOff() {
        initialize()
        adsAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "killed"}""")
        val error = format(VideoAds(Content("ep", "en")), AdFormat.PAUSE).exceptionOrNull()
        assertTrue(error is VideoAdException.Disabled)
        assertEquals(KontextVideo.State.Disabled("killed"), KontextVideo.state)
    }

    @Test fun destroySettlesHeldAdsAndEndsEverything() {
        initialize()
        val notUsed = """[{"event": "notUsed", "url": "https://t.test/n?r=[KONTEXT_REASON]"}]"""
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("overlay", Fixtures.overlay(tracking = notUsed))).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.OVERLAY).getOrThrow()
        ads.destroy()
        assertEquals(listOf("https://t.test/n?r=destroyed"), beacons)
        assertEquals(VideoAd.InvalidationReason.Destroyed, ad.invalidationReason)
        assertTrue(format(ads, AdFormat.OVERLAY).exceptionOrNull() is VideoAdException.Destroyed)
        ads.destroy() // idempotent
        assertEquals(1, beacons.size)
    }

    @Test fun rejectTellsTheServerWhyAndTheIdIsNeverTakenBack() {
        initialize()
        val notUsed = """[{"event": "notUsed", "url": "https://t.test/n?r=[KONTEXT_REASON]&p=[KONTEXT_WINNINGPRICE]"}]"""
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("overlay", Fixtures.overlay(tracking = notUsed))).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.OVERLAY).getOrThrow()
        ad.reject(VideoAd.RejectReason.Outbid(winningPrice = 9.5))
        assertEquals(listOf("https://t.test/n?r=outbid&p=9.5"), beacons)
        try {
            ad.reject()
            fail("rejected twice")
        } catch (error: VideoAdException.Invalidated) {
            assertEquals(VideoAd.InvalidationReason.Rejected, error.reason)
        }
        val error = format(ads, AdFormat.OVERLAY).exceptionOrNull()
        assertEquals("the server re-served a spent id", NoFillReason.AlreadyUsed, (error as VideoAdException.NoFill).reason)
        ads.destroy()
    }

    @Test fun anExpiredAdIsDroppedSilently() {
        initialize()
        var now = 0L
        Environment.now = { now }
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("overlay", Fixtures.overlay(expires = 1_000))).toString())
        val ads = VideoAds(Content("ep", "en"))
        val ad = format(ads, AdFormat.OVERLAY).getOrThrow()
        now = 1_000
        assertTrue(!ad.isReady)
        try {
            ad.show(android.widget.FrameLayout(ApplicationProvider.getApplicationContext()))
            fail("expired")
        } catch (error: VideoAdException.Invalidated) {
            assertEquals(VideoAd.InvalidationReason.Expired, error.reason)
        }
        assertTrue("no notUsed for an expired ad: the server knows", beacons.isEmpty())
        ads.destroy()
    }
}
