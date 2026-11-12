package so.kontext.video.internal.content

import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.ContentPlayer
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameNavigation
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.AdGeometry

/** The parts that need Android classes: player tracking, placement arithmetic, a ready page that may not replace itself. */
@RunWith(RobolectricTestRunner::class)
class AndroidPartsTest {
    @After fun restore() = Environment.reset()

    /** A player whose listener calls run the app's code. */
    class HookPlayer(override var currentPositionMillis: Long = 0) : ContentPlayer {
        override var isPlaying = true
        val listeners = mutableListOf<ContentPlayer.Listener>()
        var onRemove: (() -> Unit)? = null

        override fun addListener(listener: ContentPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: ContentPlayer.Listener) {
            listeners -= listener
            onRemove?.invoke()
        }
    }

    @Test fun aTrackInTheOldListenersRemovalWinsAndStillHandsTheOverlayOver() {
        val playback = ContentPlayback()
        val changes = mutableListOf<ContentPlayback.Change>()
        playback.onChange = { changes += it }
        playback.isInlineAdShown = { true }
        val first = HookPlayer()
        val middle = HookPlayer()
        val last = HookPlayer(currentPositionMillis = 77)
        playback.track(first)
        changes.clear() // the overlay followed the first player from here
        first.onRemove = { playback.track(last) }
        playback.track(middle)
        assertEquals(77, playback.playheadMillis)
        assertEquals(1, last.listeners.size)
        assertTrue("the call that removed it installs nothing", middle.listeners.isEmpty())
        assertEquals("the overlay hears the change once", 1, changes.count { it == ContentPlayback.Change.ContentChanged })
        playback.stop()
    }

    @Test fun aStopInTheOldListenersRemovalLeavesNothingTracked() {
        val playback = ContentPlayback()
        val first = HookPlayer()
        val next = HookPlayer()
        playback.track(first)
        first.onRemove = { playback.stop() }
        playback.track(next)
        assertFalse(playback.isTracking)
        assertTrue(next.listeners.isEmpty())
    }

    @Test fun aPauseFromThePlayersListenerIsDeliveredAndTicksRunOnlyWhileAsked() {
        val playback = ContentPlayback()
        val changes = mutableListOf<ContentPlayback.Change>()
        playback.onChange = { changes += it }
        val player = HookPlayer()
        playback.track(player)
        player.listeners.single().onIsPlayingChanged(false)
        assertEquals(listOf(ContentPlayback.Change.PlayingChanged(false)), changes)
        playback.isTicking = true
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(2, changes.count { it == ContentPlayback.Change.Tick })
        playback.isTicking = false
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(2, changes.count { it == ContentPlayback.Change.Tick })
        playback.stop()
        assertTrue(player.listeners.isEmpty())
    }

    @Test fun withoutAPlayerTheContentCountsAsPlayingAndAGoneOneAsPaused() {
        val playback = ContentPlayback()
        assertTrue(playback.isPlaying)
        val player = HookPlayer().apply { isPlaying = false }
        playback.track(player)
        assertFalse(playback.isPlaying)
    }

    @Test fun anAdIsScaledToFitNeverEnlargedAndRefusedBelowHalf() {
        val size = AdPayload.Size(320, 80)
        // 1080 px wide at density 3: 360 dp of room, the ad at its own 320 dp is 960 px, centred at the bottom.
        assertEquals(Rect(60, 1680, 1020, 1920), AdGeometry.frame(size, AdPayload.Position.BOTTOM, 1080, 1920, Rect(), 3f))
        // 480 px of room for 960: half size, the smallest allowed.
        assertEquals(Rect(0, 0, 480, 120), AdGeometry.frame(size, AdPayload.Position.TOP, 480, 1920, Rect(), 3f))
        assertNull(AdGeometry.frame(size, AdPayload.Position.TOP, 470, 1920, Rect(), 3f))
        assertNull("not scalable", AdGeometry.frame(size.copy(isScalable = false), AdPayload.Position.TOP, 900, 1920, Rect(), 3f))
        assertNull("insets that leave no room", AdGeometry.room(1080, 1920, Rect(0, 0, 0, 2000)))
        assertEquals(AdGeometry.PixelSize(480 + 10, 120 + 200), AdGeometry.minimum(size, Rect(0, 0, 10, 200), 3f))
    }

    private fun bridge() = FrameBridge(ApplicationProvider.getApplicationContext(), "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = false)

    @Test fun onceReadyThePageMayNotReplaceItself() {
        val bridge = bridge()
        assertEquals(FrameNavigation.NavigationDecision.ALLOW, bridge.navigationDecision("https://frames.test/redirect", true, null))
        assertEquals(FrameNavigation.NavigationDecision.FAIL_LOAD, bridge.navigationDecision("https://brand.test", true, null))
        bridge.receive("""{"bidId":"ad-1","type":"ad.loaded"}""", Origin.of("https://frames.test"), true)
        val current = "https://frames.test/v2/frame/ad-1"
        assertEquals(FrameNavigation.NavigationDecision.CANCEL, bridge.navigationDecision(current, true, current))
        assertEquals(FrameNavigation.NavigationDecision.ALLOW, bridge.navigationDecision("$current#card", true, current))
        assertEquals(FrameNavigation.NavigationDecision.ALLOW, bridge.navigationDecision("https://cdn.test/x", false, current))
    }
}
