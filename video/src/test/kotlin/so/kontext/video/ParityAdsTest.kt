package so.kontext.video

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The rules of the Swift suite's `VideoAds`, loading, initialization and integration tests that the other Kotlin
 * suites leave out: `format()` against a local server, `load()` and `reject()` through a page that reports what
 * the test says, `/config` and its one attempt, and the whole loop.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass") // one suite for four Swift files, so the parity table maps one place
class ParityAdsTest {
    private val server = MockWebServer()
    private val received = ConcurrentLinkedQueue<Pair<String, String>>()
    private val beacons = mutableListOf<String>()
    private val owners = mutableListOf<VideoAds>()
    private var now = 1_000L

    @Volatile private var configAnswer: MockResponse = MockResponse().setBody("""{"enabled": true}""")

    @Volatile private var adsAnswer: MockResponse = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", ad("img-0"))).toString())

    /** While closed, the server holds the next request of that path until opened. */
    @Volatile private var configGate: CountDownLatch? = null

    @Volatile private var adsGate: CountDownLatch? = null

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                received += path to request.body.readUtf8()
                return when (path) {
                    "/v2/config" -> {
                        configGate?.await(10, TimeUnit.SECONDS)
                        configAnswer
                    }
                    "/v2/video/ads" -> {
                        adsGate?.await(10, TimeUnit.SECONDS)
                        adsAnswer
                    }
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        Environment.sendBeacon = { url, _ -> beacons += url }
        Environment.assertionFailure = {}
        Environment.now = { now }
    }

    @After fun stop() {
        configGate?.countDown()
        adsGate?.countDown()
        owners.forEach { it.destroy() }
        Sdk.resetForTesting()
        Environment.reset()
        Logger.sink = null
        server.shutdown()
    }

    // region Helpers

    private val configuration get() = Configuration("test-token") { adServerUrl = server.url("/").toString() }

    private fun initialize(configuration: Configuration = this.configuration): KontextVideo.State {
        var state: KontextVideo.State? = null
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration) { state = it }
        await { state != null }
        return state!!
    }

    private fun videoAds(id: String = "ep", labels: Map<String, String> = emptyMap()) = VideoAds(Content(id, "en"), labels).also { owners += it }

    /** Runs the main looper until [done], with real time for the HTTP threads. */
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    /** Runs the main looper for [millis] of real time: what has not happened by then is taken as not happening. */
    private fun settle(millis: Long = 60) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private class Call<T> {
        lateinit var job: Job
        var result: Result<T>? = null
    }

    /** Starts [work] on the main thread; its result, or what it threw, lands in the call. */
    private fun <T> call(work: suspend () -> T): Call<T> {
        val call = Call<T>()
        call.job = Environment.scope.launch { call.result = runCatching { work() } }
        return call
    }

    private fun <T> Call<T>.settled(): Result<T> {
        await { result != null }
        return result!!
    }

    private fun format(ads: VideoAds, format: AdFormat = AdFormat.PAUSE): Result<VideoAd> = call { ads.format(format) }.settled()

    private fun ok(ads: VideoAds, format: AdFormat = AdFormat.PAUSE): VideoAd = format(ads, format).getOrThrow()

    private inline fun <reified T : Throwable> Result<*>.failure(): T {
        val error = exceptionOrNull()
        if (error !is T) fail("expected ${T::class.simpleName}, got ${error ?: getOrNull()}")
        return error as T
    }

    private inline fun <reified T : VideoAdException> assertThrows(block: () -> Unit): T = runCatching(block).failure()

    private fun serve(vararg entries: String) {
        adsAnswer = MockResponse().setBody(Fixtures.ads(*entries).toString())
    }

    private fun serveAd(id: String, format: String = "pause", expires: Long = 600_000): Unit = serve(Fixtures.entry(format, ad(id, format, expires)))

    private fun adsBodies(): List<JsonObject> = received.filter { it.first == "/v2/video/ads" }.map { Json.parseToJsonElement(it.second).jsonObject }

    private fun configCount() = received.count { it.first == "/v2/config" }

    private fun JsonObject.held(): Set<String> = this["held"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** The beacons of [event] that fired, each as its query values. */
    private fun fired(event: String): List<Map<String, String>> = beacons.map { url ->
        url.substringAfter('?').split('&').associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }
    }.filter { it["e"] == event }

    /** Sends the ad's page a message, as its main frame. */
    private fun page(ad: VideoAd, type: String, fields: String = "") {
        val bridge = checkNotNull(ad.loadedBridge) { "no page for ${ad.id}" }
        bridge.receive("""{"adId":"${ad.id}","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", FrameBridge.Origin.of(ad.payload.frameUrl), true)
    }

    /** `load()` with the page reporting `ad.loaded`. */
    private fun load(ad: VideoAd) {
        val loading = call { ad.load() }
        if (loading.result == null) page(ad, "ad.loaded")
        loading.settled().getOrThrow()
    }

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

    private class Player(var position: Long = 0) : VideoAdsPlayer {
        var listener: VideoAdsPlayer.Listener? = null
        override val currentPositionMillis get() = position
        override val isPlaying = true

        override fun addListener(listener: VideoAdsPlayer.Listener) {
            this.listener = listener
        }

        override fun removeListener(listener: VideoAdsPlayer.Listener) {
            if (this.listener === listener) this.listener = null
        }
    }

    private class Recorder : VideoAdListener {
        val reasons = mutableListOf<VideoAd.EndReason>()
        val heard = mutableListOf<String>()

        override fun onAdImpression(ad: VideoAd) {
            heard += "impression"
        }

        override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
            heard += "failed"
        }

        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            heard += "ended"
            reasons += reason
        }
    }

    // endregion

    // region The request

    @Test fun creatingAVideoAdsSendsNothing() {
        initialize()
        videoAds()
        settle()
        assertTrue(adsBodies().isEmpty())
    }

    @Test fun formatAsksForAllFormatsWithTheSessionAndWhatItHolds() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-p1")), Fixtures.entry("linear", ad("vid-l1", "linear")))
        val ads = videoAds()
        assertEquals("img-p1", ok(ads).id)
        val first = adsBodies()[0]
        assertNull("no formats: every request is for all of them", first["formats"])
        assertTrue(first.held().isEmpty())
        val session = first["sessionId"]!!.jsonPrimitive.content
        assertTrue(session.isNotEmpty())
        ok(ads, AdFormat.LINEAR)
        val second = adsBodies()[1]
        assertEquals("one session per VideoAds", session, second["sessionId"]!!.jsonPrimitive.content)
        assertEquals("both unshown ads are listed", setOf("img-p1", "vid-l1"), second.held())
    }

    @Test fun labelsGoWithEveryRequestAndNoneAreLeftOut() {
        initialize()
        serveAd("img-l1")
        val ads = videoAds("ep-l", mapOf("stage" to "first_episode", "layout" to "inline"))
        ok(ads)
        ok(ads)
        val labels = adsBodies().map { body -> body["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } }
        assertEquals(List(2) { mapOf("stage" to "first_episode", "layout" to "inline") }, labels)
        ok(videoAds("ep-n"))
        assertNull("none: left out", adsBodies()[2]["labels"])
    }

    @Test fun labelsThatBreakTheRulesAreDroppedWithOneWarningAndWhatIsKeptIsSent() {
        initialize()
        val warnings = mutableListOf<String>()
        Logger.sink = { level, message -> if (level == Configuration.LogLevel.WARNING && "labels of video" in message) warnings += message }
        val labels = mutableMapOf("stage" to "unlock", "Stage" to "x", "bad-key" to "x", "empty" to "", "long" to "é".repeat(33))
        repeat(25) { labels["k$it"] = "v" }
        val ads = videoAds("ep-d", labels)
        assertEquals(20, ads.labels.size)
        assertTrue(listOf("Stage", "bad-key", "empty", "long").none { it in ads.labels })
        assertEquals("one warning names them all", 1, warnings.size)
        assertTrue("bad-key" in warnings[0] && "more than 20" in warnings[0])
        serveAd("vid-d1", "linear")
        ok(ads, AdFormat.LINEAR)
        val sent = adsBodies()[0]["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals("what the property holds is what goes", ads.labels, sent)
    }

    @Test fun everyVideoAdsHasItsOwnSession() {
        initialize()
        assertTrue(videoAds("a").sessionId != videoAds("b").sessionId)
    }

    @Test fun thePlayerIsTheScreenAndThePlayheadIsReadAtRequestTime() {
        initialize()
        val player = Player()
        val ads = videoAds()
        ads.track(player)
        player.position = 12_500
        serveAd("img-p2")
        ok(ads)
        val body = adsBodies()[0]["player"]!!.jsonObject
        val metrics = ApplicationProvider.getApplicationContext<android.app.Application>().resources.displayMetrics
        assertEquals((metrics.widthPixels / metrics.density).roundToInt(), body["width"]!!.jsonPrimitive.int)
        assertEquals((metrics.heightPixels / metrics.density).roundToInt(), body["height"]!!.jsonPrimitive.int)
        assertEquals("true", body["fullscreen"]!!.jsonPrimitive.content)
        assertEquals(12_500, body["playhead"]!!.jsonPrimitive.int)
    }

    @Test fun aPrefetchForTheNextEpisodeUsesContentTimeZero() {
        initialize()
        val current = videoAds("100001-6")
        current.track(Player(position = 80_000))
        val next = videoAds("100001-7")
        serveAd("vid-next", "linear")
        load(ok(next, AdFormat.LINEAR))
        val body = adsBodies().last()
        assertEquals("the player's 80 s belong to the current episode", 0, body["player"]!!.jsonObject["playhead"]!!.jsonPrimitive.int)
        assertEquals("100001-7", body["video"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun anEmptyIdOrLanguageAssertsAndBadMetadataDoesNot() {
        initialize()
        val assertions = mutableListOf<String>()
        Environment.assertionFailure = { assertions += it }
        owners += VideoAds(
            Content("v", "en") {
                durationMillis = 0
                episodeNumber = 0
            },
        )
        assertTrue("metadata left out is a warning, not a programmer error", assertions.isEmpty())
        owners += VideoAds(Content("", "en"))
        owners += VideoAds(Content("v", ""))
        assertEquals(2, assertions.size)
        assertTrue("id is empty" in assertions[0] && "language is empty" in assertions[1])
    }

    // endregion

    // region Following the server

    @Test fun aReServedAdIsTheSameObjectStillLoaded() {
        initialize()
        serveAd("img-p4")
        val ads = videoAds()
        val first = ok(ads)
        load(first)
        val bridge = first.loadedBridge
        val second = ok(ads)
        assertSame("same id: the same ad", first, second)
        assertTrue(second.isLoaded)
        val again = call { second.load() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("loaded: returns at once", again.result!!.isSuccess)
        assertSame("nothing rendered again", bridge, second.loadedBridge)
    }

    @Test fun aReServedPayloadIsIgnored() {
        initialize()
        serveAd("img-p5", expires = 3_600_000)
        val ads = videoAds()
        val ad = ok(ads)
        serve(Fixtures.entry("pause", ad("img-p5", expires = 1, price = 99.0)))
        val again = ok(ads)
        now += 10
        assertSame(ad, again)
        assertEquals("the same id holds the same payload", 7.5, again.price.value, 0.0)
        assertFalse("and keeps its original deadline", again.isExpired)
    }

    @Test fun otherFormatsWaitForTheirOwnCall() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-p6")), Fixtures.entry("overlay", ad("img-o6", "overlay")))
        val ads = videoAds()
        ok(ads)
        assertEquals("img-o6", ok(ads, AdFormat.OVERLAY).id)
        assertEquals(setOf("img-p6", "img-o6"), adsBodies()[1].held())
    }

    @Test fun aNewIdReplacesTheHeldAdAndTheOldOneRefusesShowAndLoadSilently() {
        initialize()
        serveAd("img-p7")
        val ads = videoAds()
        val old = ok(ads)
        load(old)
        serveAd("img-p8")
        assertEquals("img-p8", ok(ads).id)
        assertEquals("the server replaces an ad only once it expired", VideoAd.InvalidationReason.Expired, old.invalidationReason)
        assertEquals(VideoAd.InvalidationReason.Expired, assertThrows<VideoAdException.Invalidated> { old.show(container()) }.reason)
        assertEquals(VideoAd.InvalidationReason.Expired, call { old.load() }.settled().failure<VideoAdException.Invalidated>().reason)
        assertTrue("no notice: the server replaced it, it knows", fired("notUsed").isEmpty())
    }

    @Test fun aNoFillDropsTheHeldAdAndTheNextCallAsksAgainHoldingNothing() {
        initialize()
        serveAd("img-p9")
        val ads = videoAds()
        val held = ok(ads)
        serve(Fixtures.entry("pause", noFill = "frequencyCap"))
        assertEquals(NoFillReason.FrequencyCap, format(ads).failure<VideoAdException.NoFill>().reason)
        assertEquals(VideoAd.InvalidationReason.Expired, held.invalidationReason)
        serveAd("img-p10")
        assertEquals("the next call asks again", "img-p10", ok(ads).id)
        assertTrue(adsBodies()[2].held().isEmpty())
    }

    @Test fun anExpiredAdIsDroppedSilentlyBeforeTheNextRequest() {
        initialize()
        serveAd("img-p11", expires = 30_000)
        val ads = videoAds()
        val ad = ok(ads)
        now += 31_000
        serveAd("img-p12")
        assertEquals("the next format gets a new ad", "img-p12", ok(ads).id)
        assertTrue("an expired ad is not listed", adsBodies()[1].held().isEmpty())
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
        assertTrue("no expiry notice: the server knows expires", fired("notUsed").isEmpty())
    }

    @Test fun aReServedIdThatExpiredDuringTheRequestNeverComesBack() {
        initialize()
        serveAd("img-p13", expires = 30_000)
        val ads = videoAds()
        val ad = ok(ads)
        adsGate = CountDownLatch(1)
        val again = call { ads.format(AdFormat.PAUSE) }
        await { adsBodies().size == 2 }
        now += 31_000
        adsGate!!.countDown()
        assertEquals(NoFillReason.AlreadyUsed, again.settled().failure<VideoAdException.NoFill>().reason)
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
    }

    @Test fun aHeldAdThatExpiredDuringTheRequestLeavesWithAnAnswerThatOmitsItsFormat() {
        initialize()
        serveAd("img-e1", expires = 30_000)
        val ads = videoAds()
        val ad = ok(ads)
        adsGate = CountDownLatch(1)
        val linear = call { ads.format(AdFormat.LINEAR) }
        await { adsBodies().size == 2 }
        assertEquals("valid when the request left: held", setOf("img-e1"), adsBodies()[1].held())
        now += 31_000
        serveAd("vid-e1", "linear")
        adsGate!!.countDown()
        assertEquals("vid-e1", linear.settled().getOrThrow().id)
        assertEquals("the answer left pause out, and the ad expired meanwhile: it leaves", VideoAd.InvalidationReason.Expired, ad.invalidationReason)
        assertNull(ads.ads[AdFormat.PAUSE])
        assertTrue("silently: the server knows expires", fired("notUsed").isEmpty())
    }

    @Test fun anAdThatExpiresBeforeItsWaiterResumesIsNotReturned() {
        initialize(
            Configuration("test-token") {
                adServerUrl = server.url("/").toString()
                logLevel = Configuration.LogLevel.INFO
            },
        )
        serveAd("img-e2", expires = 30_000)
        val ads = videoAds()
        val ad = ok(ads)
        serveAd("vid-e2", "linear")
        // The clock passes the pause ad's expiry after the answer swept the expired ads, before the waiter resumes.
        Logger.sink = { _, message -> if ("LINEAR for ep: ad vid-e2" in message) now += 31_000 }
        try {
            assertEquals("no ad expired at the answer: the reason is unknown", NoFillReason.Unknown, format(ads).failure<VideoAdException.NoFill>().reason)
        } finally {
            Logger.level = Configuration.LogLevel.WARNING
        }
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
        assertTrue(fired("notUsed").isEmpty())
    }

    @Test fun anIdHeldUnderAnotherFormatMakesTheAnswerMalformedAndTheHeldAdStays() {
        initialize()
        for ((heldFormat, askedFormat) in listOf(AdFormat.OVERLAY to AdFormat.PAUSE, AdFormat.PAUSE to AdFormat.OVERLAY)) {
            val id = "img-x-${heldFormat.wireName}"
            serveAd(id, heldFormat.wireName)
            val ads = videoAds("ep-${heldFormat.wireName}")
            val held = ok(ads, heldFormat)
            // The next answer leaves the held format out (so its ad is kept) and brings the same id under the other.
            serveAd(id, askedFormat.wireName)
            format(ads, askedFormat).failure<VideoAdException.Request>()
            assertNull("$askedFormat: nothing taken from the answer", ads.ads[askedFormat])
            assertSame("$heldFormat: the held ad stays", held, ads.ads[heldFormat])
            assertNull(held.invalidationReason)
            assertSame("a malformed answer: the held ad comes back", held, ok(ads, heldFormat))
            held.reject()
            assertTrue("one bid, rejected once: nothing of it is left", ads.ads.values.none { it.id == id })
        }
    }

    // endregion

    // region Failures

    @Test fun aFailedRequestLeavesNothingBehindAndTheNextCallSendsAFreshOne() {
        initialize()
        adsAnswer = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        val ads = videoAds()
        format(ads).failure<VideoAdException.Request>()
        serveAd("img-after-failure")
        assertEquals("the failed request is not reused", "img-after-failure", ok(ads).id)
        assertEquals(2, adsBodies().size)
        assertTrue(adsBodies()[1].held().isEmpty())
    }

    @Test fun serverErrorsAreRequestErrorsNeverRetried() {
        initialize()
        adsAnswer = MockResponse().setResponseCode(400).setBody("""{"errors": [{"status": "400", "code": "invalidRequest"}]}""")
        format(videoAds()).failure<VideoAdException.Request>()
        settle()
        assertEquals(1, adsBodies().size)
    }

    @Test fun aFailedConfigIsTriedAgainByFormatAndThrowsWhenThatFailsToo() {
        configAnswer = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        initialize()
        val configs = configCount()
        format(videoAds()).failure<VideoAdException.InitializationFailed>()
        assertEquals("format() tried /config again", configs + 1, configCount())
    }

    @Test fun destroyWhileWaitingForConfigThrowsDestroyedAtOnceAndTheOthersShareOneConfig() {
        configGate = CountDownLatch(1)
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration)
        serveAd("img-init")
        val destroyed = videoAds("v1")
        val other = videoAds("v2")
        val call = call { destroyed.format(AdFormat.PAUSE) }
        val otherCall = call { other.format(AdFormat.PAUSE) }
        await { configCount() == 1 }
        destroyed.destroy()
        call.settled().failure<VideoAdException.Destroyed>()
        assertEquals("it finished while /config was still out", 1L, configGate!!.count)
        configGate!!.countDown()
        assertEquals("the other video still waits for /config and gets its ad", "img-init", otherCall.settled().getOrThrow().id)
        assertEquals(1, configCount())
        assertEquals("the destroyed video sent nothing", 1, adsBodies().size)
    }

    @Test fun formatAndAwaitInitializationWaitForConfigInFlight() {
        configGate = CountDownLatch(1)
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration)
        serveAd("img-p15")
        val call = call { videoAds("v").format(AdFormat.PAUSE) }
        val waiting = call { KontextVideo.awaitInitialization() }
        await { configCount() == 1 }
        settle()
        assertTrue("nothing leaves before /config answers", adsBodies().isEmpty())
        assertNull(waiting.result)
        configGate!!.countDown()
        assertEquals("img-p15", call.settled().getOrThrow().id)
        assertEquals(KontextVideo.State.Enabled, waiting.settled().getOrThrow())
    }

    // endregion

    // region The kill switch

    @Test fun aDisabledResponseSwitchesTheSdkOffAndDropsEveryAdWithoutNotices() {
        initialize()
        serveAd("img-p16")
        val ads = videoAds()
        val other = videoAds("other")
        val loaded = ok(other)
        load(loaded)
        val held = ok(ads)
        adsAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        assertEquals("publisherDisabled", format(ads, AdFormat.LINEAR).failure<VideoAdException.Disabled>().reason)
        assertEquals(KontextVideo.State.Disabled("publisherDisabled"), KontextVideo.state)
        call { held.load() }.settled().failure<VideoAdException.Disabled>()
        assertThrows<VideoAdException.Disabled> { loaded.show(container()) }
        format(other, AdFormat.OVERLAY).failure<VideoAdException.Disabled>()
        assertTrue("no notices: the server switched the SDK off, it knows", fired("notUsed").isEmpty())
    }

    @Test fun aKillSwitchDuringALoadMakesItThrowDisabled() {
        initialize()
        serveAd("dead-p16b")
        val ads = videoAds()
        val ad = ok(ads)
        val loading = call { ad.load() }
        adsAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        format(ads, AdFormat.LINEAR)
        assertEquals("publisherDisabled", loading.settled().failure<VideoAdException.Disabled>().reason)
    }

    @Test fun switchedOffWhileAFailingRequestWasOutTheHeldAdDoesNotComeBack() {
        initialize()
        serveAd("img-held-off")
        val ads = videoAds()
        ok(ads)
        adsGate = CountDownLatch(1)
        adsAnswer = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        val call = call { ads.format(AdFormat.PAUSE) }
        await { adsBodies().size == 2 }
        Sdk.disable("publisherDisabled")
        adsGate!!.countDown()
        assertEquals("disabled, not the held ad", "publisherDisabled", call.settled().failure<VideoAdException.Disabled>().reason)
    }

    // endregion

    // region The shared request and its races

    @Test fun callsForDifferentFormatsWhileARequestRunsShareIt() {
        initialize()
        adsGate = CountDownLatch(1)
        serve(Fixtures.entry("pause", ad("img-p17")), Fixtures.entry("linear", ad("vid-l17", "linear")))
        val ads = videoAds()
        val pause = call { ads.format(AdFormat.PAUSE) }
        val linear = call { ads.format(AdFormat.LINEAR) }
        await { adsBodies().size == 1 }
        adsGate!!.countDown()
        assertEquals("img-p17", pause.settled().getOrThrow().id)
        assertEquals("vid-l17", linear.settled().getOrThrow().id)
        assertEquals("one request for both", 1, adsBodies().size)
    }

    @Test fun anAdRejectedWhileARequestRunsIsNotTakenBackAndOtherFormatsAreServed() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-p20")), Fixtures.entry("linear", ad("vid-l20", "linear")))
        val ads = videoAds()
        val ad = ok(ads)
        adsGate = CountDownLatch(1)
        val pause = call { ads.format(AdFormat.PAUSE) }
        val linear = call { ads.format(AdFormat.LINEAR) }
        await { adsBodies().size == 2 }
        assertEquals("the request listed it before the reject", setOf("img-p20", "vid-l20"), adsBodies()[1].held())
        ad.reject()
        adsGate!!.countDown()
        assertEquals("a spent id is never accepted again", NoFillReason.AlreadyUsed, pause.settled().failure<VideoAdException.NoFill>().reason)
        assertEquals("vid-l20", linear.settled().getOrThrow().id)
        assertEquals("one notice, from the reject", listOf("img-p20"), fired("notUsed").map { it["id"] })
    }

    @Test fun anAdShownWhileARequestRunsIsNotTakenBackAndStaysOnScreen() {
        initialize()
        serveAd("img-p21")
        val ads = videoAds()
        val ad = ok(ads)
        load(ad)
        adsGate = CountDownLatch(1)
        val again = call { ads.format(AdFormat.PAUSE) }
        await { adsBodies().size == 2 }
        ad.show(container())
        adsGate!!.countDown()
        assertEquals(NoFillReason.AlreadyUsed, again.settled().failure<VideoAdException.NoFill>().reason)
        assertTrue("the ad on screen is untouched", ad.wasShown && !ad.hasEnded)
    }

    // endregion

    // region Destroying

    @Test fun destroyEndsEverythingWithOneNoticePerHeldAd() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-p22")), Fixtures.entry("overlay", ad("img-o22", "overlay")), Fixtures.entry("linear", ad("vid-l22", "linear")))
        val ads = videoAds()
        val loaded = ok(ads)
        load(loaded)
        val unloaded = ok(ads, AdFormat.LINEAR)
        val showing = ok(ads, AdFormat.OVERLAY)
        load(showing)
        val recorder = Recorder()
        showing.listener = recorder
        val container = container()
        showing.show(container)

        ads.destroy()
        assertEquals(listOf(VideoAd.EndReason.DESTROYED), recorder.reasons)
        assertEquals("nothing of the episode stays on screen", 0, container.childCount)
        assertEquals("every held ad, loaded or not; not the ad on screen", setOf("img-p22", "vid-l22"), fired("notUsed").map { it["id"] }.toSet())
        assertTrue(fired("notUsed").all { it["r"] == "destroyed" })
        assertThrows<VideoAdException.Destroyed> { loaded.show(container) }
        call { unloaded.load() }.settled().failure<VideoAdException.Destroyed>()
        format(ads).failure<VideoAdException.Destroyed>()

        ads.destroy()
        assertEquals("destroy twice does nothing more", 1, recorder.reasons.size)
        assertEquals(2, fired("notUsed").size)
    }

    @Test fun destroyNotifiesValidHeldAdsOnlyAnExpiredOneLeavesSilently() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-d-old", expires = 30_000)), Fixtures.entry("overlay", ad("img-d-new", "overlay")))
        val ads = videoAds()
        val old = ok(ads)
        val fresh = ok(ads, AdFormat.OVERLAY)
        now += 31_000 // the pause ad expired and nothing called the SDK since
        ads.destroy()
        assertEquals(VideoAd.InvalidationReason.Expired, old.invalidationReason)
        assertEquals(VideoAd.InvalidationReason.Destroyed, fresh.invalidationReason)
        assertEquals("one notice, for the valid ad", listOf("img-d-new"), fired("notUsed").map { it["id"] })
    }

    @Test fun aSlowUserAgentIsWaitedForAtMostTheCapThenTheRequestGoesWithAFallback() {
        initialize()
        val agent = CompletableDeferred<String?>()
        Environment.userAgent = { agent.await() }
        Environment.userAgentWaitMillis = 300
        serveAd("img-ua1")
        val first = call { videoAds("v1").format(AdFormat.PAUSE) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("nothing sent while within the wait", adsBodies().isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertEquals("img-ua1", first.settled().getOrThrow().id)
        fun sentAgent(index: Int) = adsBodies()[index]["device"]!!.jsonObject["network"]!!.jsonObject["userAgent"]!!.jsonPrimitive.content
        val fallback = sentAgent(0)
        assertTrue("the WebView's shape: $fallback", fallback.startsWith("Mozilla/5.0 (Linux; Android ") && "; wv) AppleWebKit/537.36" in fallback)
        // The read went on; the next request carries the real one.
        agent.complete("Mozilla/5.0 real")
        serveAd("img-ua2")
        assertEquals("img-ua2", call { videoAds("v2").format(AdFormat.PAUSE) }.settled().getOrThrow().id)
        assertEquals("Mozilla/5.0 real", sentAgent(1))
    }

    @Test fun destroyWhileTheUserAgentIsReadThrowsDestroyedAtOnceAndTheOthersGoOn() {
        initialize()
        val agent = CompletableDeferred<String?>()
        Environment.userAgent = { agent.await() }
        serveAd("img-ua")
        val destroyed = videoAds("v1")
        val other = videoAds("v2")
        val call = call { destroyed.format(AdFormat.PAUSE) }
        val otherCall = call { other.format(AdFormat.PAUSE) }
        shadowOf(Looper.getMainLooper()).idle()
        destroyed.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("released while the user agent is still being read", call.result!!.exceptionOrNull() is VideoAdException.Destroyed)
        agent.complete("Mozilla/5.0 test")
        assertEquals("the other video waited for the same read", "img-ua", otherCall.settled().getOrThrow().id)
        assertEquals("the destroyed video sent nothing", 1, adsBodies().size)
        assertEquals("Mozilla/5.0 test", adsBodies()[0]["device"]!!.jsonObject["network"]!!.jsonObject["userAgent"]!!.jsonPrimitive.content)
    }

    @Test fun destroyReleasesTheTrackedPlayer() {
        initialize()
        val ads = videoAds()
        val player = Player(position = 12_000)
        ads.track(player)
        assertEquals(12_000, ads.playheadMillis)
        assertNotNull(player.listener)
        ads.destroy()
        assertNull("destroy removed its listener", player.listener)
        assertEquals(0, ads.playheadMillis)
        assertFalse(ads.playback.isTracking)
    }

    @Test fun thePlayheadIsTheTrackedPlayersClampedOrZero() {
        initialize()
        val ads = videoAds()
        assertEquals("nothing tracked: the start of the video, what a prefetch sends", 0, ads.playheadMillis)
        val player = Player(position = 47_000)
        ads.track(player)
        assertEquals(47_000, ads.playheadMillis)
        player.position = -3_000
        assertEquals("never negative", 0, ads.playheadMillis)
        player.position = Long.MAX_VALUE
        assertEquals("a huge value is clamped", Tracker.MAX_PLAYHEAD_MILLIS, ads.playheadMillis)
        ads.track(object : VideoAdsPlayer {
            override val currentPositionMillis: Long get() = error("released")
            override val isPlaying = false
        })
        assertEquals("tracking another player replaces the first; one that throws reads 0", 0, ads.playheadMillis)
    }

    // endregion

    // region Loading

    @Test fun anAdGivesItsFactsAndRendersNothingBeforeLoad() {
        initialize()
        serveAd("img-1")
        val ad = ok(videoAds())
        assertEquals("img-1", ad.id)
        assertEquals(AdFormat.PAUSE, ad.format)
        assertEquals(VideoAd.Price(7.5, "USD", VideoAd.Price.Precision.EXACT), ad.price)
        assertNull(ad.durationMillis)
        assertNull(ad.invalidationReason)
        assertFalse(ad.isLoaded || ad.isReady)
        assertNull("nothing rendered before load()", ad.loadedBridge)
    }

    @Test fun showBeforeLoadThrowsNotLoadedAndLeavesTheAdUsable() {
        initialize()
        serveAd("img-4b")
        val ad = ok(videoAds())
        val container = container()
        assertThrows<VideoAdException.NotLoaded> { ad.show(container) }
        assertNull("untouched: load() still works", ad.invalidationReason)
        load(ad)
        ad.show(container)
        ad.dismiss()
    }

    @Test fun loadsAtOnceShareTheSameError() {
        initialize()
        serveAd("err-5")
        val ad = ok(videoAds())
        val one = call { ad.load() }
        val two = call { ad.load() }
        val bridge = ad.loadedBridge
        page(ad, "ad.error", """"code": 502""")
        for (result in listOf(one.settled(), two.settled())) assertEquals(502, result.failure<VideoAdException.CreativeFailed>().code)
        assertNotNull("one page for both", bridge)
        assertEquals(1, fired("error").size)
    }

    @Test fun aFailingCreativeThrowsItsCodeFiresErrorAndIsNotHeld() {
        initialize()
        serveAd("err-7")
        val ads = videoAds()
        val ad = ok(ads)
        val loading = call { ad.load() }
        page(ad, "ad.error", """"code": 502""")
        assertEquals(502, loading.settled().failure<VideoAdException.CreativeFailed>().code)
        assertEquals(listOf("502"), fired("error").map { it["code"] })
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(502), ad.invalidationReason)
        serveAd("img-8")
        ok(ads)
        assertTrue("a failed ad is not held", adsBodies().last().held().isEmpty())
    }

    @Test fun cancellingALoadStopsOnlyItsWaitingTheLoadGoesOn() {
        initialize()
        serveAd("slow-6")
        val ad = ok(videoAds())
        val first = call { ad.load() }
        val bridge = ad.loadedBridge
        first.job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the caller stopped waiting", first.result!!.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val second = call { ad.load() }
        page(ad, "ad.loaded")
        second.settled().getOrThrow()
        assertTrue(ad.isReady)
        assertSame("the cancelled load went on; nothing rendered again", bridge, ad.loadedBridge)
    }

    @Test fun expiryCountsFromTheFirstReceiptOnTheSdksClock() {
        initialize()
        serveAd("img-e", expires = 1_000)
        val ad = ok(videoAds())
        val receivedAt = now
        now = receivedAt + 999
        assertFalse("just before", ad.isExpired)
        now = receivedAt + 1_000
        assertTrue("exactly at the lifetime", ad.isExpired)
        now = receivedAt + 1_001
        assertTrue("just after", ad.isExpired)
    }

    @Test fun anExpiredAdThrowsAtLoadAndRendersNothing() {
        initialize()
        serveAd("img-12", expires = 30_000)
        val ad = ok(videoAds())
        now += 31_000
        assertEquals(VideoAd.InvalidationReason.Expired, call { ad.load() }.settled().failure<VideoAdException.Invalidated>().reason)
        assertNull(ad.loadedBridge)
        assertTrue("expiry is not a creative error", fired("error").isEmpty())
    }

    @Test fun anAdThatExpiresDuringTheLoadDropsTheResult() {
        initialize()
        serveAd("slow-13", expires = 30_000)
        val ad = ok(videoAds())
        val loading = call { ad.load() }
        now += 31_000
        page(ad, "ad.loaded")
        assertEquals(VideoAd.InvalidationReason.Expired, loading.settled().failure<VideoAdException.Invalidated>().reason)
        assertFalse(ad.isReady)
        assertTrue(fired("error").isEmpty())
    }

    // endregion

    // region Reject

    @Test fun rejectBeforeLoadFiresNotUsedAndLoadThrowsAndTheAdIsNotHeld() {
        initialize()
        serveAd("img-15")
        val ads = videoAds()
        val ad = ok(ads)
        ad.reject()
        assertEquals(listOf("notUsed"), fired("notUsed").map { it["r"] })
        assertEquals(VideoAd.InvalidationReason.Rejected, call { ad.load() }.settled().failure<VideoAdException.Invalidated>().reason)
        serveAd("img-16")
        ok(ads)
        assertTrue("a rejected ad is not held", adsBodies().last().held().isEmpty())
    }

    @Test fun rejectOutbidCarriesTheWinningPriceAndTheWinnerBothOptional() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-17")), Fixtures.entry("overlay", ad("img-17b", "overlay")))
        val ads = videoAds()
        ok(ads).reject(VideoAd.RejectReason.Outbid(winningPrice = 2.4, winner = "Other Network"))
        val beacon = fired("notUsed").single()
        assertEquals("outbid", beacon["r"])
        assertEquals("2.4", beacon["wp"])
        assertEquals("Other Network", beacon["w"])
        ok(ads, AdFormat.OVERLAY).reject(VideoAd.RejectReason.Outbid())
        val bare = fired("notUsed").last()
        assertEquals("outbid", bare["r"])
        assertTrue(bare["wp"]!!.isEmpty() && bare["w"]!!.isEmpty())
    }

    @Test fun rejectDuringTheLoadEndsItAndReleasesTheWebView() {
        initialize()
        serveAd("dead-18")
        val ad = ok(videoAds())
        val loading = call { ad.load() }
        val bridge = ad.loadedBridge!!
        ad.reject()
        assertEquals(VideoAd.InvalidationReason.Rejected, loading.settled().failure<VideoAdException.Invalidated>().reason)
        assertNull(ad.loadedBridge)
        assertEquals(FrameBridge.State.ENDED, bridge.state)
        assertEquals(1, fired("notUsed").size)
    }

    @Test fun rejectAfterLoadMakesShowThrow() {
        initialize()
        serveAd("img-19")
        val ad = ok(videoAds())
        load(ad)
        ad.reject()
        assertEquals(VideoAd.InvalidationReason.Rejected, ad.invalidationReason)
        assertFalse(ad.isReady)
        assertEquals(VideoAd.InvalidationReason.Rejected, assertThrows<VideoAdException.Invalidated> { ad.show(container()) }.reason)
        assertEquals(1, fired("notUsed").size)
    }

    @Test fun rejectAnExpiredAdThrowsWithoutANotice() {
        initialize()
        serveAd("img-22", expires = 30_000)
        val ad = ok(videoAds())
        now += 31_000
        assertEquals(VideoAd.InvalidationReason.Expired, assertThrows<VideoAdException.Invalidated> { ad.reject() }.reason)
        assertTrue("the server closes an expired ad itself", fired("notUsed").isEmpty())
    }

    @Test fun anAdOfADestroyedVideoAdsThrowsDestroyedAtLoadAndReject() {
        initialize()
        serveAd("img-23")
        val ads = videoAds()
        val ad = ok(ads)
        ads.destroy()
        call { ad.load() }.settled().failure<VideoAdException.Destroyed>()
        assertThrows<VideoAdException.Destroyed> { ad.reject() }
    }

    @Test fun theWebViewLivesWhileTheAdIsHeldAndGoesWhenItEnds() {
        initialize()
        serveAd("img-rel")
        val ads = videoAds()
        load(ok(ads))
        val ad = ok(ads)
        val bridge = ad.loadedBridge
        assertNotNull("not using the ad is not a rejection: the held ad keeps its page", bridge)
        ad.reject()
        assertNull("rejecting releases its WebView", ad.loadedBridge)
        assertEquals(FrameBridge.State.ENDED, bridge!!.state)
    }

    // endregion

    // region initialize

    @Test fun startsNotInitialized() {
        assertEquals(KontextVideo.State.NotInitialized, KontextVideo.state)
        assertFalse(Sdk.isInitialized)
        assertNull(Sdk.configuration)
    }

    @Test fun initializeSendsTheTokenAndTheInstallIdAndTellsTheListener() {
        var reported: KontextVideo.State? = null
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration) { reported = it }
        assertEquals(KontextVideo.State.Initializing, KontextVideo.state)
        await { reported != null }
        assertEquals(KontextVideo.State.Enabled, reported)
        assertEquals(configuration, Sdk.configuration)
        val body = Json.parseToJsonElement(received.first { it.first == "/v2/config" }.second).jsonObject
        assertEquals("test-token", body["publisherToken"]!!.jsonPrimitive.content)
        val installId = DeviceFacts.installId(ApplicationProvider.getApplicationContext())
        assertEquals(installId, body["installId"]!!.jsonPrimitive.content)
        assertEquals("a UUID", installId, UUID.fromString(installId).toString())
        assertEquals("stable", installId, DeviceFacts.installId(ApplicationProvider.getApplicationContext()))
    }

    @Test fun aResetDuringInitializeIsNotUndoneByTheCancelledRequest() {
        configGate = CountDownLatch(1)
        var reported: KontextVideo.State? = null
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration) { reported = it }
        await { configCount() == 1 }
        Sdk.resetForTesting()
        configGate!!.countDown()
        settle(150)
        assertEquals("the cancelled request neither fails nor enables what was reset", KontextVideo.State.NotInitialized, KontextVideo.state)
        assertNull("and its listener is never told", reported)
    }

    @Test fun anUnusableConfigurationAssertsAtInitialize() {
        assertTrue("a local mock", Configuration("p") { adServerUrl = "http://localhost:5175" }.problems.isEmpty())
        val assertions = mutableListOf<String>()
        Environment.assertionFailure = { assertions += it }
        initialize(Configuration("") { adServerUrl = server.url("/").toString() })
        assertEquals(listOf("KontextVideo.Configuration: publisherToken is empty"), assertions)
    }

    @Test fun aSecondInitializeIsIgnoredAndTellsNoListener() {
        initialize()
        var told = 0
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("other") { adServerUrl = server.url("/").toString() }) { told++ }
        settle()
        assertEquals("the first configuration stays", "test-token", Sdk.configuration!!.publisherToken)
        assertEquals("an ignored call tells no listener", 0, told)
        assertEquals(1, configCount())
        assertEquals("the state the first call produced", KontextVideo.State.Enabled, call { KontextVideo.awaitInitialization() }.settled().getOrThrow())
    }

    @Test fun initializeAppliesTheLogLevel() {
        initialize(
            Configuration("test-token") {
                adServerUrl = server.url("/").toString()
                logLevel = Configuration.LogLevel.NONE
            },
        )
        assertEquals(Configuration.LogLevel.NONE, Logger.level)
    }

    @Test fun aDisabledConfigIsFinalForTheLaunch() {
        configAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        assertEquals(KontextVideo.State.Disabled("publisherDisabled"), initialize())
        val ads = videoAds()
        assertEquals("publisherDisabled", format(ads).failure<VideoAdException.Disabled>().reason)
        format(ads).failure<VideoAdException.Disabled>()
        assertEquals("never asked again", 1, configCount())
    }

    @Test fun aFailedConfigIsOneAttemptAndEveryRequestTriesOnceMore() {
        configAnswer = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        assertEquals(KontextVideo.State.Failed, initialize())
        assertEquals("one attempt, no retry", 1, configCount())
        val ads = videoAds()
        format(ads).failure<VideoAdException.InitializationFailed>()
        format(ads).failure<VideoAdException.InitializationFailed>()
        assertEquals("two more attempts, one request each", 3, configCount())
        assertEquals(KontextVideo.State.Failed, KontextVideo.state)
        assertTrue(adsBodies().isEmpty())
    }

    @Test fun theListenerIsToldExactlyOnceForEveryOutcome() {
        val outcomes = listOf(
            MockResponse().setBody("""{"enabled": true}""") to KontextVideo.State.Enabled,
            MockResponse().setBody("""{"enabled": false, "disabledReason": "publisherDisabled"}""") to KontextVideo.State.Disabled("publisherDisabled"),
            MockResponse().setResponseCode(503) to KontextVideo.State.Failed,
        )
        for ((answer, expected) in outcomes) {
            Sdk.resetForTesting()
            configAnswer = answer
            val reported = mutableListOf<KontextVideo.State>()
            KontextVideo.initialize(ApplicationProvider.getApplicationContext(), configuration) { reported += it }
            await { reported.isNotEmpty() }
            // A failed /config is tried again by the next request; that attempt belongs to the request, not to initialize.
            format(VideoAds(Content("ep", "en")))
            settle()
            assertEquals("$expected", listOf(expected), reported)
        }
    }

    @Test fun theUserAgentIsReadOnlyOnceTheServerEnabledTheSdk() {
        var reads = 0
        Environment.userAgent = {
            reads++
            "test-agent"
        }
        configAnswer = MockResponse().setBody("""{"enabled": false, "disabledReason": "publisherDisabled"}""")
        initialize()
        settle()
        assertEquals("a switched-off SDK makes no WebView", 0, reads)
        Sdk.resetForTesting()
        configAnswer = MockResponse().setBody("""{"enabled": true}""")
        initialize()
        await { reads == 1 }
        assertTrue("started by the enabled /config, before any ads request", adsBodies().isEmpty())
    }

    @Test fun reportDebugInTheConfigEnablesTheDebugReporter() {
        configAnswer = MockResponse().setBody("""{"enabled": true, "reportDebug": true}""")
        initialize()
        assertTrue(DebugReporter.isEnabled)
    }

    @Test fun aVideoAdsBeforeInitializeAssertsAndOnceInitializedNothingDoes() {
        val messages = mutableListOf<String>()
        Environment.assertionFailure = { messages += it }
        owners += VideoAds(Content("v", "en"))
        assertEquals(1, messages.size)
        assertTrue("initialize()" in messages[0])
        initialize()
        messages.clear()
        assertTrue(Sdk.requireInitialized("x"))
        assertTrue(messages.isEmpty())
    }

    @Test fun configIsSentOnceWhateverTheFailure() {
        val failures = listOf(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            MockResponse().setResponseCode(503).setBody("""{"errors": [{"status": "503", "code": "unavailable"}]}"""),
            MockResponse().setResponseCode(429).setBody("""{"errors": [{"status": "429", "code": "rateLimited"}]}"""),
            MockResponse().setResponseCode(400).setBody("""{"errors": [{"status": "400", "code": "invalidRequest"}]}"""),
        )
        for (failure in failures) {
            Sdk.resetForTesting()
            configAnswer = failure
            val before = configCount()
            assertEquals(KontextVideo.State.Failed, initialize())
            settle()
            assertEquals("no retry: a network failure, a 503, a 429, a 400 alike", before + 1, configCount())
        }
    }

    // endregion

    // region The whole loop

    /** The loop of the lifecycle: prepare early (format, load), show at the pause, reject when outbid, destroy. */
    @Test fun theWholeLoopPrepareEarlyShowAtThePauseRejectWhenOutbid() {
        initialize()
        serve(Fixtures.entry("pause", ad("img-loop1")), Fixtures.entry("overlay", ad("img-loopo", "overlay")))
        val ads = videoAds()
        val pause = ok(ads)
        load(pause)
        val recorder = Recorder()
        pause.listener = recorder
        val container = container()
        pause.show(container)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(SdkTest.FRAME_MILLIS)) // the first layout: the impression
        assertEquals(listOf("impression"), recorder.heard)
        assertEquals(1, fired("impression").size)
        pause.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertEquals(0, container.childCount)

        serve(Fixtures.entry("pause", ad("img-loop2")), Fixtures.entry("overlay", ad("img-loopo", "overlay")))
        val next = ok(ads)
        assertEquals("the shown ad is never served again", "img-loop2", next.id)
        assertEquals("the overlay ad is still held", setOf("img-loopo"), adsBodies().last().held())
        next.reject(VideoAd.RejectReason.Outbid(winningPrice = 3.0))
        assertEquals("nothing rendered, one notice", listOf("notUsed"), beacons.filter { "id=img-loop2&" in it }.map { it.substringAfter("&e=").substringBefore('&') })
        val overlay = ok(ads, AdFormat.OVERLAY)
        assertEquals("re-served unchanged", "img-loopo", overlay.id)
        ads.destroy()
        assertEquals("destroy closes the held overlay ad", listOf("img-loop2", "img-loopo"), fired("notUsed").map { it["id"] })
    }

    // endregion

    /** A server ad whose every beacon says its ad, its event and the macros the tests read. */
    private fun ad(id: String, format: String = "pause", expires: Long = 600_000, price: Double = 7.5): String {
        val tracking = listOf("notUsed", "loaded", "error", "impression").joinToString(prefix = "[", postfix = "]") { event ->
            """{"event": "$event", "url": "https://t.test/b?id=$id&e=$event&r=[KONTEXT_REASON]&wp=[KONTEXT_WINNINGPRICE]&w=[KONTEXT_WINNER]&code=[ERRORCODE]"}"""
        }
        val common = """"id": "$id", "format": "$format", "trigger": {"kind": "manual"}, "expires": $expires,
            "price": {"value": $price, "currency": "USD", "precision": "exact"},
            "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/$id"}, "tracking": $tracking"""
        return when (format) {
            "linear" -> """{$common, "creative": "video", "duration": 30000, "skipOffset": 5000}"""
            "overlay" -> """{$common, "creative": "image", "position": "bottom", "sizes": [{"width": 320, "height": 80}], "duration": null}"""
            else -> """{$common, "creative": "image", "position": "top", "sizes": [{"width": 300, "height": 250, "scalable": false}]}"""
        }
    }
}
