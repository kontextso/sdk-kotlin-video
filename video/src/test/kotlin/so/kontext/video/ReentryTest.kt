package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.handle
import so.kontext.video.internal.measurement.ActiveTimeClock
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.support.Environment

/**
 * The app's code runs inside the SDK's: a listener or a player read may end the ad on the spot, and whatever
 * the SDK was about to do next must then not happen. And what arrives after the end changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ReentryTest : SdkTest() {
    private class Player(var playing: Boolean = true, var position: Long = 0) : VideoAdsPlayer {
        var onRead: (() -> Unit)? = null
        override val currentPositionMillis: Long get() = position.also { onRead?.invoke() }
        override val isPlaying get() = playing
        val listeners = mutableListOf<VideoAdsPlayer.Listener>()
        override fun addListener(listener: VideoAdsPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: VideoAdsPlayer.Listener) {
            listeners -= listener
        }
    }

    @Test fun aListenerThatDismissesAtTheImpressionHearsOnlyTheEnd() {
        val ad = overlay(tracking = tracking("creativeView", "impression", "overlayViewDuration"))
        val heard = mutableListOf<String>()
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                heard += "impression"
                ad.dismiss()
            }

            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                heard += "ended $reason"
            }
        }
        ad.show(container())
        laidOut()
        assertEquals(listOf("impression", "ended DISMISSED"), heard)
        assertTrue(ad.hasEnded)
        assertNull("the slot is free", so.kontext.video.internal.core.Sdk.adOnScreen)
        assertEquals(listOf("creativeView", "impression", "overlayViewDuration"), events)
    }

    @Test fun aPlayheadReadThatEndsTheAdDropsTheClick() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner, tracking = """[{"event": "click", "url": "https://t.test/b?e=click&p=[CONTENTPLAYHEAD]"}]""")
        val recorder = Recorder()
        ad.listener = recorder
        ad.show(container())
        laidOut()
        player.onRead = { ad.dismiss() }
        ad.handle(FrameEvent.Click("https://brand.test", null, VideoAd.Click.Area.Creative))
        // The playhead is read once, before the click is handled: the ad ended there, so no beacon and no click.
        assertEquals(listOf("impression", "ended"), recorder.heard)
        assertFalse(events.contains("click"))
    }

    @Test fun eventsAfterTheEndChangeNothing() {
        val ad = linear(tracking = tracking("start", "complete", "click", "error", "skip", "close"))
        val recorder = Recorder()
        ad.listener = recorder
        ad.show(activity())
        ad.end(VideoAd.EndReason.INTERRUPTED)
        val before = events.toList()
        for (late in listOf(
            FrameEvent.Video(FrameEvent.VideoEvent.START, 0),
            FrameEvent.Click("https://brand.test", null, VideoAd.Click.Area.Creative),
            FrameEvent.Complete(30_000),
            FrameEvent.Error(405),
            FrameEvent.Skip(6_000),
            FrameEvent.Close(FrameEvent.CloseReason.CLOSE),
            FrameEvent.Progress(10_000, 10_000),
        )) {
            ad.handle(late)
        }
        ad.fail(VideoAdException.CreativeFailed(405))
        ad.recordImpression()
        assertEquals(before, events)
        assertEquals(listOf("ended"), recorder.heard)
    }

    @Test fun anAdEndedBeforeShowIgnoresItsPage() {
        val owner = owner()
        val ad = overlay(owner)
        ad.invalidate(VideoAd.InvalidationReason.Destroyed)
        ad.handle(FrameEvent.Loaded)
        ad.handle(FrameEvent.Error(500))
        assertEquals(VideoAd.InvalidationReason.Destroyed, ad.invalidationReason)
        ad.invalidate(VideoAd.InvalidationReason.Expired)
        assertEquals("the first reason stays", VideoAd.InvalidationReason.Destroyed, ad.invalidationReason)
        assertTrue(events.isEmpty())
    }

    @Test fun anOverlaysWindowThatComesBackDoesNotCountWhatPlayedWhileAway() {
        var now = 0L
        Environment.now = { now }
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, duration = "3000")
        ad.listener = recorder
        ad.show(container())
        val clock = ad.clock!!
        clock.windowVisibilityChanged(visible = true) // already counting: nothing changes
        now = 1_000
        player.position = 1_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        clock.windowVisibilityChanged(visible = false)
        // Away: the content plays on, picture-in-picture, without the overlay on screen.
        player.position = 60_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("nothing counts while the window is hidden", 1_000, ad.contentProgress!!.elapsedMillis)
        clock.windowVisibilityChanged(visible = true)
        now = 2_000
        player.position = 61_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("counting resumes from where the content is", 2_000, ad.contentProgress!!.elapsedMillis)
        assertTrue(recorder.reasons.isEmpty())
        now = 3_000
        player.position = 62_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
    }

    @Test fun anOverlayOverAPlayerThatStoppedEndsAtTheNextShowOfAnotherAd() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val first = overlay(owner)
        val recorder = Recorder()
        first.listener = recorder
        first.show(container())
        player.playing = false // no listener call: a player that stopped without saying so
        val second = pause(owner)
        second.show(container())
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertTrue(second.wasShown)
    }

    @Test fun aChangeForAnAdNotOnScreenIsIgnored() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val ad = overlay(owner)
        owner.playback.onChange?.invoke(ContentPlayback.Change.ContentChanged)
        assertFalse("not shown: nothing to end", ad.hasEnded)
        assertNull(ad.invalidationReason)
    }

    @Test fun theClockIsIdempotentAndCountsOnlyWhileVisible() {
        var now = 0L
        Environment.now = { now }
        var elapsed = 0
        val clock = ActiveTimeClock(1_000, "ad-1") { elapsed += 1 }
        clock.windowVisibilityChanged(visible = true) // before start: ignored
        assertFalse(clock.isCounting)
        clock.start(visible = false)
        clock.start(visible = true) // a second start: ignored
        assertFalse(clock.isCounting)
        var activations = 0
        clock.onActivate = { activations += 1 }
        clock.windowVisibilityChanged(visible = true)
        assertEquals(1, activations)
        now = 400
        clock.windowVisibilityChanged(visible = false)
        clock.windowVisibilityChanged(visible = false)
        now = 10_000
        assertEquals(400, clock.elapsedMillis)
        clock.windowVisibilityChanged(visible = true)
        now = 10_600
        idle(600)
        assertEquals("the remaining 600 ms ran out once", 1, elapsed)
        assertEquals(1_000, clock.stop())
        assertEquals("a second stop returns the same", 1_000, clock.stop())
        clock.windowVisibilityChanged(visible = true)
        assertFalse("stopped for good", clock.isCounting)
    }

    @Test fun aStoppedClockNeverElapses() {
        val clock = ActiveTimeClock(500, "ad-1") { throw AssertionError("elapsed after stop") }
        clock.start(visible = true)
        clock.stop()
        idle(1_000)
        val endless = ActiveTimeClock(null, "ad-2") { throw AssertionError("no duration, no end") }
        endless.start(visible = true)
        idle(100_000)
        assertTrue(endless.isCounting)
    }
}
