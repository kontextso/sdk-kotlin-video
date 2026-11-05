package so.kontext.video

import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.support.Environment
import java.time.Duration

/** The lifecycle details the first review parked for the rewrite (S-LIFE-3, 7–11, the load timeout). */
@RunWith(RobolectricTestRunner::class)
class DeferredLifecycleTest : SdkTest() {
    private fun playing(playing: Boolean = true) = object : ContentPlayer {
        override val currentPositionMillis = 0L
        override val isPlaying = playing
    }

    @Test fun anAdIsNotReadyWhileTheSdkIsSwitchedOff() {
        val ad = overlay()
        assertTrue(ad.isReady)
        Sdk.disable("paused")
        assertFalse("show would throw Disabled", ad.isReady)
    }

    @Test fun loadOnAShownAdReturnsEvenOnceItsBidExpired() {
        var now = Environment.now()
        Environment.now = { now }
        val ad = overlay()
        ad.show(container())
        now += 365L * 24 * 3_600_000
        var result: Result<Unit>? = null
        Environment.scope.launch { result = runCatching { ad.load() } }
        idle()
        assertTrue("nothing to load: ${result?.exceptionOrNull()}", result!!.isSuccess)
    }

    @Test fun aCreativeThatFailedAfterItLoadedMakesLoadThrowCreativeFailed() {
        val ad = overlay()
        page(ad, "ad.error", """"code": 405""")
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(405), ad.invalidationReason)
        var result: Result<Unit>? = null
        Environment.scope.launch { result = runCatching { ad.load() } }
        idle()
        assertEquals(405, (result!!.exceptionOrNull() as VideoAdException.CreativeFailed).code)
    }

    @Test fun theFirstTrackWhileAnOverlayIsUpLeavesIt() {
        val owner = owner()
        val ad = overlay(owner)
        val recorder = Recorder().also { ad.listener = it }
        ad.show(container())
        owner.track(playing())
        idle()
        assertFalse("it followed no player before: nothing changed for it", ad.hasEnded)
        owner.track(playing())
        idle()
        assertEquals("another player than the one it followed: the content changed", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aPageThatEndsItselfCompletedMarksTheAdCompleted() {
        val ad = overlay()
        val recorder = Recorder().also { ad.listener = it }
        ad.show(container())
        page(ad, "ad.close", """"reason": "complete"""")
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.isCompleted)
    }

    @Test fun theHeldListIsReadOnceTheRequestIsBuilt() {
        var reads = 0
        val built = runBlocking {
            AdsRequest.make(Environment.context!!, "token", Content("ep", "en"), emptyMap(), 0, "session", {
                reads += 1
                listOf("h-1")
            })
        }
        assertEquals(1, reads)
        assertEquals(listOf("h-1"), built.held)
        assertEquals("[\"h-1\"]", built.body["held"].toString())
    }

    @Test fun theLoadTimeoutCountsTheTimeThatPassedNotTheTicks() {
        FrameBridge.loadTimeoutMillis = 1_000
        Environment.isAppInForeground = { true }
        try {
            val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
            var result: Result<Unit>? = null
            Environment.scope.launch { result = runCatching { ad.load() } }
            idle()
            // A busy main thread: the first tick comes 1.2 s late. Counted per tick, that was 250 ms.
            val before = SystemClock.uptimeMillis()
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_200))
            assertTrue(SystemClock.uptimeMillis() - before >= 1_200)
            idle()
            assertEquals(500, (result?.exceptionOrNull() as? VideoAdException.CreativeFailed)?.code)
            assertNull(ad.loadedBridge)
        } finally {
            FrameBridge.loadTimeoutMillis = 10_000
        }
    }
}
