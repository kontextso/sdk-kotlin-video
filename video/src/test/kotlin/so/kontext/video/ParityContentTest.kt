package so.kontext.video

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.playback.ContentProgress
import so.kontext.video.internal.screen.AdHostView
import so.kontext.video.internal.support.Environment
import so.kontext.video.media3.track
import java.lang.ref.WeakReference
import androidx.media3.common.Player as Media3Player

/**
 * An overlay over a tracked player, as the Swift suite has it: a pause, the end or another item ends it as
 * INTERRUPTED, `show` refuses paused content, its duration counts milliseconds of video, and the app's code
 * inside the player's calls (a destroy, another track) leaves nothing behind.
 */
@RunWith(RobolectricTestRunner::class)
class ParityContentTest : SdkTest() {
    private val tick get() = ContentPlayback.tickIntervalMillis

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

    /** A custom player whose reads and listener calls may run the app's code. */
    private class Player(var playing: Boolean = true, var position: Long = 0) : VideoAdsPlayer {
        override val currentPositionMillis: Long get() = position
        override val isPlaying: Boolean
            get() {
                onPlayingRead?.invoke()
                return playing
            }
        override var playbackSpeed: Float? = null
        val listeners = mutableListOf<VideoAdsPlayer.Listener>()

        /** Every listener ever given, removed or not. */
        val given = mutableListOf<VideoAdsPlayer.Listener>()
        var onPlayingRead: (() -> Unit)? = null

        /** Runs inside `addListener`, before the listener is kept. */
        var beforeAdding: (() -> Unit)? = null

        /** Runs inside `addListener`, once the listener is kept. */
        var afterAdding: (() -> Unit)? = null
        var onRemove: (() -> Unit)? = null

        override fun addListener(listener: VideoAdsPlayer.Listener) {
            beforeAdding?.invoke()
            listeners += listener
            given += listener
            afterAdding?.invoke()
        }

        override fun removeListener(listener: VideoAdsPlayer.Listener) {
            listeners -= listener
            onRemove?.invoke()
        }
    }

    /** An adapter that holds its player weakly, as the SDK asks: once the player is gone, nothing plays. */
    private class WeakPlayer(target: Any) : VideoAdsPlayer {
        val target = WeakReference(target)
        override val currentPositionMillis = 0L
        override val isPlaying: Boolean get() = target.get() != null
    }

    /** A container whose add-view hook the test fills: app code that runs while `show` attaches the ad. */
    private fun hooking(onAdd: () -> Unit): FrameLayout {
        val root = container()
        val hooking = object : FrameLayout(root.context) {
            override fun onViewAdded(child: View) {
                super.onViewAdded(child)
                if (child is AdHostView) onAdd()
            }
        }
        root.addView(hooking)
        hooking.layout(0, 0, 320, 400)
        return hooking
    }

    /** A Media3 player whose playing facts the test sets, and the listener the adapter registered on it. */
    private class Media3 {
        var playWhenReady = true
        var state = Media3Player.STATE_READY
        val listener = slot<Media3Player.Listener>()
        val player = mockk<Media3Player>(relaxed = true).also { player ->
            every { player.applicationLooper } returns Looper.getMainLooper()
            every { player.playWhenReady } answers { playWhenReady }
            every { player.playbackState } answers { state }
            every { player.playbackSuppressionReason } returns Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE
            every { player.contentPosition } returns 0
            every { player.addListener(capture(listener)) } returns Unit
        }
    }

    @Test fun aPauseReadOnATickEndsTheOverlayAsInterruptedAndFreesTheScreen() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        val container = container()
        ad.show(container)
        idle(tick * 2)
        assertTrue("playing: it stays", recorder.reasons.isEmpty())
        player.playing = false // no listener call: only a tick reads it
        idle(tick)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(0, container.childCount)
        assertFalse(ad.isCompleted)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun showOverPausedContentThrowsWithNothingHeardAndWorksOnceItPlays() {
        val player = Player(playing = false)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, tracking = tracking("creativeView", "impression"))
        ad.listener = recorder
        val container = container()
        assertThrows<VideoAdException.ContentPaused> { ad.show(container) }
        assertTrue(ad.isReady && !ad.wasShown)
        assertTrue(recorder.heard.isEmpty() && events.isEmpty())
        assertEquals(0, container.childCount)
        player.playing = true
        ad.show(container)
        assertTrue(ad.wasShown)
        ad.dismiss()
    }

    @Test fun aPlayingTrackedOverlayStillBlocksAnotherAd() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val overlay = overlay(owner)
        val pause = pause(owner)
        val container = container()
        overlay.show(container)
        assertThrows<VideoAdException.AnotherAdShowing> { pause.show(container) }
        assertFalse(overlay.hasEnded)
        assertSame(overlay, Sdk.adOnScreen)
        overlay.dismiss()
    }

    @Test fun theDurationCountsVideoTimeNotStallsSeeksOrWallTime() {
        var now = 0L
        Environment.now = { now }
        val player = Player(position = 10_000)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, duration = "1000")
        ad.listener = recorder
        ad.show(container())
        val elapsed = { ad.contentProgress!!.elapsedMillis }
        // Playing but not moving (a stall) for longer than the duration, in the looper's time and the SDK's.
        now += 5_000
        idle(5_000)
        assertEquals("a stall counts nothing", 0L, elapsed())
        assertTrue("and no wall-time expiry", recorder.reasons.isEmpty())
        now += 500
        player.position = 10_500
        idle(tick)
        assertEquals(500L, elapsed())
        now += 250
        player.position = 200_000 // a seek forward
        idle(tick)
        assertEquals("the seek counts nothing", 500L, elapsed())
        now += 300
        player.position = 200_300
        idle(tick)
        assertEquals(800L, elapsed())
        assertTrue(recorder.reasons.isEmpty())
        now += 300
        player.position = 200_600
        idle(tick)
        assertEquals("1.1 s of video: the duration elapsed", listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.isCompleted)
    }

    @Test fun theEndOfAMedia3ItemEndsTheOverlayWhilePlayWhenReadyStays() {
        val media3 = Media3()
        val owner = owner()
        owner.track(media3.player)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        ad.show(container())
        media3.state = Media3Player.STATE_ENDED
        media3.listener.captured.onPlaybackStateChanged(Media3Player.STATE_ENDED)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertTrue("the player still asks to play", media3.player.playWhenReady)
        assertFalse("but its item ended", owner.playback.isPlaying)
    }

    @Test fun anEndedMedia3ItemRefusesTheNextOverlayUntilItPlaysAgain() {
        val media3 = Media3()
        val owner = owner()
        owner.track(media3.player)
        val first = overlay(owner)
        val recorder = Recorder()
        first.listener = recorder
        val container = container()
        first.show(container)
        media3.state = Media3Player.STATE_ENDED
        media3.listener.captured.onPlaybackStateChanged(Media3Player.STATE_ENDED)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        val next = overlay(owner)
        val nextRecorder = Recorder()
        next.listener = nextRecorder
        assertThrows<VideoAdException.ContentPaused> { next.show(container) }
        assertTrue(next.isReady && nextRecorder.heard.isEmpty())
        // A pause and a play without a seek restart nothing: the item is still at its end.
        media3.playWhenReady = false
        media3.listener.captured.onPlayWhenReadyChanged(false, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        media3.playWhenReady = true
        media3.listener.captured.onPlayWhenReadyChanged(true, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertThrows<VideoAdException.ContentPaused> { next.show(container) }
        // The app plays it again from the start.
        media3.state = Media3Player.STATE_READY
        media3.listener.captured.onPlaybackStateChanged(Media3Player.STATE_READY)
        next.show(container)
        assertTrue(next.wasShown)
        next.dismiss()
    }

    @Test fun aCallbackOfAPlayerTrackedBeforeEndsNothing() {
        val old = Player()
        val owner = owner()
        owner.track(old)
        val oldListener = old.listeners.single()
        // A call from another thread: it reaches the main thread only after the next track.
        Thread { oldListener.onIsPlayingChanged(false) }.apply { start() }.join()
        val fresh = Player()
        owner.track(fresh)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        ad.show(container())
        idle()
        oldListener.onContentChanged()
        oldListener.onIsPlayingChanged(false)
        assertTrue("the old player's calls belong to a binding that is gone", recorder.reasons.isEmpty())
        ad.dismiss()
    }

    @Test fun aPauseFromTheListenerWhileTheHostIsAttachedEndsTheOverlayBeforeAnImpression() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, tracking = tracking("creativeView", "impression"))
        ad.listener = recorder
        var endedInThePause = false
        val container = hooking {
            player.playing = false
            player.listeners.toList().forEach { it.onIsPlayingChanged(false) }
            endedInThePause = ad.hasEnded
        }
        ad.show(container)
        assertTrue("the pause ended the overlay, reserved but not yet on screen", endedInThePause)
        assertEquals(listOf("ended"), recorder.heard)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertTrue("no impression over paused content", events.isEmpty())
        assertEquals(0, container.childCount)
    }

    @Test fun aPauseNoListenerReportedDuringTheAttachEndsItBeforeAnImpression() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, tracking = tracking("creativeView", "impression"))
        ad.listener = recorder
        val container = hooking { player.playing = false }
        ad.show(container)
        assertEquals(listOf("ended"), recorder.heard)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertTrue(events.isEmpty())
        assertEquals(0, container.childCount)
    }

    @Test fun trackingAnotherPlayerEndsTheOverlayThatFollowedTheFirst() {
        val owner = owner()
        owner.track(Player())
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        ad.show(container())
        owner.track(Player())
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertFalse("nothing on screen: no ticks", owner.playback.isTicking)
    }

    @Test fun ticksRunOnlyWithATrackedPlayerAndStartWhenOneIsTracked() {
        val owner = owner()
        val ad = pause(owner)
        ad.show(container())
        assertFalse("no player: nothing to watch, no ticks", owner.playback.isTicking)
        // A pause ad stays through a newly tracked player (an overlay would end): the ticks start for it.
        owner.track(Player(playing = false))
        assertFalse(ad.hasEnded)
        assertTrue("a player tracked while the ad is on screen starts them", owner.playback.isTicking)
    }

    @Test fun aPlayerThatIsGoneEndsTheOverlayAndRefusesTheNext() {
        val target = Any()
        val player = WeakPlayer(target)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, duration = "60000")
        ad.listener = recorder
        val container = container()
        ad.show(container)
        player.target.clear()
        idle(tick)
        assertEquals("no content left under it", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertThrows<VideoAdException.ContentPaused> { overlay(owner).show(container) }
    }

    @Test fun aShortSeekCountsNothingAndALateSampleOfRealPlaybackCounts() {
        var now = 0L
        Environment.now = { now }
        val seek = ContentProgress(durationMillis = 1_000, playheadMillis = 10_000)
        assertFalse(seek.advance(11_500, isCounting = true))
        assertEquals("1.5 s at once, before any time passed: a seek", 0L, seek.elapsedMillis)
        val late = ContentProgress(durationMillis = 10_000, playheadMillis = 0)
        now += 3_000
        assertFalse(late.advance(3_000, isCounting = true))
        assertEquals("3 s of video after 3 s: playback, sampled late", 3_000L, late.elapsedMillis)
        val jumped = ContentProgress(durationMillis = 10_000, playheadMillis = 0)
        now += 1_000
        jumped.resync(800) // a seek the player told
        assertFalse(jumped.advance(800, isCounting = true))
        assertEquals(0L, jumped.elapsedMillis)
    }

    @Test fun fasterPlaybackCountsItsVideoMilliseconds() {
        var now = 0L
        Environment.now = { now }
        val unknown = ContentProgress(durationMillis = 10_000, playheadMillis = 0)
        val known = ContentProgress(durationMillis = 100_000, playheadMillis = 0)
        for (second in 1..4) {
            now += 250
            unknown.advance(second * 1_000L, isCounting = true) // 4×, the speed not told
            known.advance(second * 2_000L, isCounting = true, speed = 8f) // 8×, as the player says
        }
        assertEquals(4_000L, unknown.elapsedMillis)
        assertEquals(8_000L, known.elapsedMillis)
        val seek = ContentProgress(durationMillis = 10_000, playheadMillis = 0)
        now += 250
        assertFalse(seek.advance(2_000, isCounting = true, speed = 1f))
        assertEquals("2 s in a quarter second at 1×: a seek", 0L, seek.elapsedMillis)
    }

    @Test fun aPauseReportedOffTheMainThreadEndsTheOverlayEvenIfPlaybackResumedBeforeDelivery() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        ad.show(container())
        val listener = player.listeners.single()
        Thread {
            listener.onIsPlayingChanged(false)
            listener.onIsPlayingChanged(true)
        }.apply { start() }.join()
        idle()
        assertEquals("the pause was reported: it counts", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun anOverlaysEndThatDestroysTheIncomingAdsOwnerStopsItsShow() {
        val player = Player()
        val overlayOwner = owner()
        overlayOwner.track(player)
        val overlay = overlay(overlayOwner)
        val pauseOwner = owner()
        val pause = pause(pauseOwner)
        val pauseRecorder = Recorder()
        pause.listener = pauseRecorder
        overlay.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = pauseOwner.destroy()
        }
        val container = container()
        overlay.show(container)
        player.playing = false // not yet noticed: the pause ad's show ends the overlay first
        assertThrows<VideoAdException.Destroyed> { pause.show(container) }
        assertFalse(pause.wasShown)
        assertTrue(pauseRecorder.heard.isEmpty())
        assertEquals(0, container.childCount)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aPlayingReadThatDestroysTheOwnerStopsTheShow() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        player.onPlayingRead = {
            player.onPlayingRead = null
            owner.destroy()
        }
        val container = container()
        assertThrows<VideoAdException.Destroyed> { ad.show(container) }
        assertTrue(owner.isDestroyed)
        assertFalse(ad.wasShown)
        assertTrue(recorder.heard.isEmpty())
        assertEquals(0, container.childCount)
    }

    @Test fun anAdShownAndEndedInsideTheHandoverIsNotReservedAgain() {
        val player = Player()
        val overlayOwner = owner()
        overlayOwner.track(player)
        val overlay = overlay(overlayOwner)
        val pauseOwner = owner()
        val pause = pause(pauseOwner)
        val pauseRecorder = Recorder()
        pause.listener = pauseRecorder
        val container = container()
        overlay.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                pause.show(container)
                pause.dismiss()
            }
        }
        overlay.show(container)
        player.playing = false
        assertThrows<VideoAdException.AlreadyShown> { pause.show(container) }
        assertTrue(pause.hasEnded)
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), pauseRecorder.reasons)
        assertNull("an ended ad never takes the slot again", Sdk.adOnScreen)
        pauseOwner.destroy()
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aDestroyInTheOverlaysEndWhileTrackingAnotherPlayerLeavesNothingListening() {
        val owner = owner()
        owner.track(Player())
        val ad = overlay(owner)
        ad.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = owner.destroy()
        }
        ad.show(container())
        val next = Player()
        owner.track(next)
        assertTrue(ad.hasEnded && owner.isDestroyed)
        assertFalse(owner.playback.isTracking)
        assertTrue(next.listeners.isEmpty())
    }

    @Test fun aTrackInTheOverlaysEndWinsOverTheTrackThatEndedIt() {
        val owner = owner()
        owner.track(Player())
        val middle = Player()
        val last = Player(position = 100)
        val first = overlay(owner)
        first.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = owner.track(last)
        }
        val container = container()
        first.show(container)
        owner.track(middle)
        assertTrue(first.hasEnded)
        assertTrue(middle.listeners.isEmpty())
        assertEquals(1, last.listeners.size)
        assertEquals(100L, owner.playback.playheadMillis)
        val second = overlay(owner)
        val recorder = Recorder()
        second.listener = recorder
        second.show(container)
        middle.playing = false
        middle.given.forEach { it.onIsPlayingChanged(false) }
        idle(tick * 2)
        assertTrue("the player tracked and replaced in between does not end the last one's overlay", recorder.reasons.isEmpty())
        second.dismiss()
    }

    @Test fun aDestroyBeforeTheListenerIsKeptLeavesNothingListening() {
        val owner = owner()
        val player = Player()
        player.beforeAdding = { owner.destroy() }
        owner.track(player)
        assertTrue(owner.isDestroyed)
        assertFalse(owner.playback.isTracking)
        assertTrue(player.listeners.isEmpty())
    }

    @Test fun aDestroyOnceTheListenerIsKeptLeavesNothingListening() {
        val owner = owner()
        val player = Player()
        player.afterAdding = { owner.destroy() }
        owner.track(player)
        assertTrue(owner.isDestroyed)
        assertFalse(owner.playback.isTracking)
        assertTrue(player.listeners.isEmpty())
    }

    @Test fun aTrackWhileTheListenerIsAddedKeepsTheLastPlayersListener() {
        val owner = owner()
        val middle = Player()
        val last = Player(position = 99)
        middle.beforeAdding = { owner.track(last) }
        owner.track(middle)
        assertEquals(99L, owner.playback.playheadMillis)
        assertEquals(1, last.listeners.size)
        assertTrue(middle.listeners.isEmpty())
    }

    @Test fun aTrackInTheRemovalThatDestroyRunsTracksNothing() {
        val owner = owner()
        val first = Player()
        val next = Player()
        first.onRemove = { owner.track(next) }
        owner.track(first)
        owner.destroy()
        assertFalse(owner.playback.isTracking)
        assertTrue(next.listeners.isEmpty())
    }

    @Test fun destroyAndAnotherTrackLetGoOfThePlayersListener() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        assertEquals(1, player.listeners.size)
        owner.destroy()
        assertTrue("destroy removed the listener", player.listeners.isEmpty())
        assertFalse(owner.playback.isTracking)
        val second = owner(Content("ep-2", "en"))
        second.track(player)
        second.track(Player())
        assertTrue("tracking another player drops the first one's listener", player.listeners.isEmpty())
    }

    // region A pause ad follows the content

    @Test fun aPauseAdEndsAsInterruptedWhenTheContentPlaysAgainFromOutsideTheApp() {
        val player = Player(playing = false)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        val container = container()
        ad.show(container)
        idle(tick * 4)
        assertTrue("paused content: it stays", recorder.reasons.isEmpty())
        // Play from the media notification, a headset or the picture-in-picture window: not the app's own button.
        player.playing = true
        player.listeners.toList().forEach { it.onIsPlayingChanged(true) }
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(0, container.childCount)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aPauseAdEndsWhenTheNextPageOfAFeedTakesThePlayer() {
        val player = Player(playing = false)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        // The next page, still attached in the pager, takes the one shared player and plays it.
        owner().track(player)
        player.playing = true
        player.listeners.toList().forEach { it.onIsPlayingChanged(true) }
        idle(tick * 4)
        assertEquals("its episode is gone from the player", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull("the next page's ad can show", Sdk.adOnScreen)
    }

    @Test fun aPauseAdOverAPlayerWithoutAListenerEndsOnTheTickThatSeesItPlay() {
        val player = object : VideoAdsPlayer {
            var playing = false
            override val currentPositionMillis = 0L
            override val isPlaying: Boolean get() = playing
        }
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        idle(tick * 4)
        assertTrue(recorder.reasons.isEmpty())
        player.playing = true
        idle(tick)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aPauseAdShownBeforeTheAppPausesEndsOnlyWhenTheContentPlaysAgain() {
        val player = Player(playing = true)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        idle(tick * 4)
        assertTrue("shown over playing content, the app pauses next: it stays", recorder.reasons.isEmpty())
        player.playing = false
        player.listeners.toList().forEach { it.onIsPlayingChanged(false) }
        idle(tick * 4)
        assertTrue(recorder.reasons.isEmpty())
        player.playing = true
        idle(tick)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aListenerCallToPlayingEndsAPauseAdShownOverPlayingContentOnlyAfterAPause() {
        val player = Player(playing = true)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        // A call that says what was already true (a repeat, a rate change) is no resume of this ad.
        player.listeners.toList().forEach { it.onIsPlayingChanged(true) }
        assertTrue("no pause seen yet: it stays", recorder.reasons.isEmpty())
        player.playing = false
        player.listeners.toList().forEach { it.onIsPlayingChanged(false) }
        assertTrue(recorder.reasons.isEmpty())
        player.playing = true
        player.listeners.toList().forEach { it.onIsPlayingChanged(true) }
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aQueuedMedia3ResumeDoesNotEndThePauseAdThatTheOverlaysEndShowed() {
        val media3 = Media3()
        val owner = owner()
        owner.track(media3.player)
        val container = container()
        val pause = pause(owner)
        val pauseRecorder = Recorder()
        pause.listener = pauseRecorder
        val overlay = overlay(owner)
        overlay.listener = object : VideoAdListener {
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                // The app shows its pause ad as the overlay ends; the player already plays again.
                pause.show(container)
            }
        }
        overlay.show(container)
        // A pause and a play in a row: Media3 delivers each callback in turn, with the player already playing.
        media3.playWhenReady = true
        media3.listener.captured.onPlayWhenReadyChanged(false, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertTrue("the queued pause ended the overlay", overlay.hasEnded)
        assertSame(pause, Sdk.adOnScreen)
        media3.listener.captured.onPlayWhenReadyChanged(true, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertTrue("the queued resume came before any pause of the pause ad: it stays", pauseRecorder.reasons.isEmpty())
        media3.playWhenReady = false
        media3.listener.captured.onPlayWhenReadyChanged(false, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        media3.playWhenReady = true
        media3.listener.captured.onPlayWhenReadyChanged(true, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), pauseRecorder.reasons)
    }

    @Test fun anOverlayShownAsAPolledPlayerPlaysAgainEndsThePauseAdAndShows() {
        val player = object : VideoAdsPlayer {
            var playing = false
            override val currentPositionMillis = 0L
            override val isPlaying: Boolean get() = playing
        }
        val owner = owner()
        owner.track(player)
        val container = container()
        val recorder = Recorder()
        val pause = pause(owner)
        pause.listener = recorder
        pause.show(container)
        idle(tick * 2)
        assertTrue(recorder.reasons.isEmpty())
        // No callback: the player plays again and the app shows its overlay before the next tick.
        player.playing = true
        val overlay = overlay(owner)
        overlay.show(container)
        assertEquals("the pause ad over playing content ended first", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertTrue(overlay.wasShown)
        assertSame(overlay, Sdk.adOnScreen)
        overlay.dismiss()
    }

    @Test fun aSecondPauseAdOverStillPausedContentFindsTheFirstOnScreen() {
        val player = Player(playing = false)
        val owner = owner()
        owner.track(player)
        val container = container()
        val first = pause(owner)
        first.show(container)
        val second = loaded(AdPayload.parse(Fixtures.json(Fixtures.pause(id = "ad-p2"))), owner)
        assertThrows<VideoAdException.AnotherAdShowing> { second.show(container) }
        assertFalse(first.hasEnded)
        first.dismiss()
    }

    @Test fun anUntrackedPauseAdStaysUntilDismissedAndResumeStaysResumed() {
        val recorder = Recorder()
        val ad = pause()
        ad.listener = recorder
        ad.show(container())
        idle(60_000)
        assertTrue("no player: nothing to follow", recorder.reasons.isEmpty())
        page(ad, "ad.close", """"reason": "resume"""")
        assertEquals(listOf(VideoAd.EndReason.RESUMED), recorder.reasons)
    }

    // endregion

    @Test fun anOverlayEndedOnATickWhoseEndShowsTheNextLeavesOneTickPending() {
        var now = 0L
        Environment.now = { now }
        var reads = 0
        val player = object : VideoAdsPlayer {
            override val currentPositionMillis: Long get() = now
            override val isPlaying: Boolean get() = true.also { reads += 1 }
        }
        val owner = owner()
        owner.track(player)
        val container = container()
        val readsPerInterval = {
            val before = reads
            repeat(10) {
                now += tick
                idle(tick)
            }
            (reads - before) / 10.0
        }
        val staying = { overlay(owner, duration = "null") }
        // A steady state first: one overlay that stays.
        staying().also { first ->
            first.show(container)
            val steady = readsPerInterval()
            assertTrue("a tick reads the player", steady > 0)
            first.dismiss()
            // Each overlay's duration elapses on a tick and its end shows the next, which restarts the ticks;
            // the last one stays.
            var shown = 0
            lateinit var next: () -> Unit
            next = {
                shown += 1
                val ad = if (shown == 4) staying() else overlay(owner, duration = "${tick * 2}")
                ad.listener = object : VideoAdListener {
                    override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                        if (reason == VideoAd.EndReason.COMPLETED) next()
                    }
                }
                ad.show(container)
            }
            next()
            repeat(20) {
                now += tick
                idle(tick)
            }
            assertEquals("the overlays handed over", 4, shown)
            assertEquals("one tick per interval, as before the handovers", steady, readsPerInterval(), 0.01)
        }
    }
}
