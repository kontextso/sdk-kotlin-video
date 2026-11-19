package so.kontext.video

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Requests while things change around them: callers leaving, destroy, the kill switch, a failing /config. */
@RunWith(RobolectricTestRunner::class)
class RacesTest {
    private val server = MockWebServer()
    private val adsRequests = AtomicInteger()

    @Volatile private var configAnswer = MockResponse().setBody("""{"enabled": true}""")

    @Volatile private var adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())

    /** While closed, the server holds the next request of that path until opened. */
    @Volatile private var configGate: CountDownLatch? = null

    @Volatile private var adsGate: CountDownLatch? = null
    private val adsArrived = CountDownLatch(1)

    @Before fun start() {
        // Every ads request names the viewer.
        so.kontext.video.internal.state.SdkState.current.recordUser(so.kontext.video.KontextVideo.User("test-user"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v2/config" -> {
                    configGate?.await(10, TimeUnit.SECONDS)
                    configAnswer
                }
                "/v2/video/ads" -> {
                    adsRequests.incrementAndGet()
                    adsArrived.countDown()
                    adsGate?.await(10, TimeUnit.SECONDS)
                    adsAnswer
                }
                else -> MockResponse().setResponseCode(204)
            }
        }
        server.start()
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        Environment.sendBeacon = { _, _ -> null }
        Environment.assertionFailure = {}
    }

    @After fun stop() {
        configGate?.countDown()
        adsGate?.countDown()
        SdkInitializer.resetForTesting()
        Environment.reset()
        server.shutdown()
    }

    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5))
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(5)
        }
    }

    private fun initialize(wait: Boolean = true) {
        var done = false
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("t") { adServerUrl = server.url("/").toString() }) { done = true }
        if (wait) await { done }
    }

    private class Call {
        lateinit var job: Job
        var result: Result<VideoAd>? = null
    }

    /** Made before the coroutine: on the main thread it starts at once, inside launch. */
    private fun format(ads: VideoAds, format: AdFormat = AdFormat.PAUSE): Call {
        val call = Call()
        call.job = Environment.scope.launch {
            call.result = try {
                Result.success(ads.format(format))
            } catch (error: VideoAdException) {
                Result.failure(error)
            }
        }
        return call
    }

    @Test fun twoCallersShareOneRequestAndOneLeavingDoesNotStopIt() {
        initialize()
        adsGate = CountDownLatch(1)
        val ads = VideoAds(Content("ep", "en"))
        val leaving = format(ads)
        val staying = format(ads)
        await { adsArrived.count == 0L } // the main thread runs: the request is built there, the consent read off it
        leaving.job.cancel()
        adsGate!!.countDown()
        await { staying.result != null }
        assertEquals("ad-2", staying.result!!.getOrThrow().id)
        assertNull("the caller that left hears nothing", leaving.result)
        assertEquals(1, adsRequests.get())
        ads.destroy()
    }

    @Test fun aDestroyWhileConfigRunsEndsTheRequestBeforeItLeaves() {
        configGate = CountDownLatch(1)
        initialize(wait = false)
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        shadowOf(Looper.getMainLooper()).idle()
        ads.destroy()
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Destroyed)
        configGate!!.countDown()
        await { SdkState.current.phase == KontextVideo.State.Enabled }
        Thread.sleep(50)
        assertEquals("nothing left after destroy", 0, adsRequests.get())
    }

    @Test fun aDestroyWhileTheUserAgentIsReadSendsNothing() {
        initialize()
        val agent = CompletableDeferred<String?>()
        Environment.userAgent = { agent.await() }
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        shadowOf(Looper.getMainLooper()).idle()
        ads.destroy()
        agent.complete("late-agent")
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Destroyed)
        assertEquals(0, adsRequests.get())
    }

    @Test fun aDestroyDuringTheNetworkWaitDropsTheAnswer() {
        initialize()
        adsGate = CountDownLatch(1)
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        await { adsArrived.count == 0L } // the main thread runs: the request is built there, the consent read off it
        ads.destroy()
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Destroyed)
        adsGate!!.countDown()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the answer is never held", ads.ads.isEmpty())
    }

    @Test fun theKillSwitchFromAnotherVideoWinsOverAnAnswerInFlight() {
        initialize()
        adsGate = CountDownLatch(1)
        val first = VideoAds(Content("ep-1", "en"))
        val call = format(first)
        await { adsArrived.count == 0L } // the main thread runs: the request is built there, the consent read off it
        SdkInitializer.disable("killed") // another video's response switched the SDK off meanwhile
        adsGate!!.countDown()
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        assertTrue(first.ads.isEmpty())
        first.destroy()
    }

    @Test fun theKillSwitchBeforeSendingSendsNothing() {
        initialize()
        val agent = CompletableDeferred<String?>()
        Environment.userAgent = { agent.await() }
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        shadowOf(Looper.getMainLooper()).idle()
        SdkInitializer.disable("killed")
        agent.complete(null)
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        assertEquals(0, adsRequests.get())
        ads.destroy()
    }

    @Test fun aFailedRequestAfterTheKillSwitchSaysDisabled() {
        initialize()
        adsGate = CountDownLatch(1)
        adsAnswer = MockResponse().setResponseCode(500)
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        await { adsArrived.count == 0L } // the main thread runs: the request is built there, the consent read off it
        SdkInitializer.disable("killed")
        adsGate!!.countDown()
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        ads.destroy()
    }

    @Test fun aConfigThatFailsTwiceIsInitializationFailed() {
        configAnswer = MockResponse().setResponseCode(503)
        initialize()
        assertEquals(KontextVideo.State.Failed, KontextVideo.state)
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.InitializationFailed)
        assertEquals(0, adsRequests.get())
        ads.destroy()
    }

    @Test fun formatBeforeInitializeIsNotInitialized() {
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        await { call.result != null }
        assertTrue(call.result!!.exceptionOrNull() is VideoAdException.NotInitialized)
    }

    @Test fun aReplacedAdEndsTheOneHeldAndANoFillDropsIt() {
        initialize()
        val ads = VideoAds(Content("ep", "en"))
        val first = format(ads).also { c -> await { c.result != null } }.result!!.getOrThrow()
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", Fixtures.pause(id = "ad-9"))).toString())
        val second = format(ads).also { c -> await { c.result != null } }.result!!.getOrThrow()
        assertNotSame(first, second)
        assertEquals(VideoAd.InvalidationReason.Expired, first.invalidationReason)
        adsAnswer = MockResponse().setBody(Fixtures.ads(Fixtures.entry("pause", noFill = "noAdMatch")).toString())
        val noFill = format(ads).also { c -> await { c.result != null } }
        assertEquals(NoFillReason.NoAdMatch, (noFill.result!!.exceptionOrNull() as VideoAdException.NoFill).reason)
        assertEquals(VideoAd.InvalidationReason.Expired, second.invalidationReason)
        ads.destroy()
    }

    @Test fun aReServedAdIsTheSameObject() {
        initialize()
        val ads = VideoAds(Content("ep", "en"))
        val first = format(ads).also { c -> await { c.result != null } }.result!!.getOrThrow()
        val again = format(ads).also { c -> await { c.result != null } }.result!!.getOrThrow()
        assertSame(first, again)
        ads.destroy()
    }

    @Test fun aMalformedAnswerIsARequestError() {
        initialize()
        adsAnswer = MockResponse().setBody("""{"enabled": true, "formats": [{"format": "pause"}]}""")
        val ads = VideoAds(Content("ep", "en"))
        val call = format(ads)
        await { call.result != null }
        val error = call.result!!.exceptionOrNull() as VideoAdException.Request
        assertEquals(VideoAdException.RequestFailure.BadResponse, error.failure)
        ads.destroy()
    }
}
