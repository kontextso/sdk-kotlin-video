package so.kontext.video

import android.os.Looper
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.media3.track
import androidx.media3.common.Player as Media3Player

/**
 * An episode's ads only in that episode, as the Swift suite has it: a player belongs to one `VideoAds` at a
 * time, and an overlay or pause ad whose episode no longer plays in the tracked player throws
 * `Invalidated(ContentChanged)` at `show`, with no beacon. Linear ads are not checked.
 */
@RunWith(RobolectricTestRunner::class)
class VideoAdEpisodeTest : SdkTest() {
    /** A custom player, with an item report when [item] is set. */
    private class Player(var item: Any? = null, var reportsItem: Boolean = false) : ContentPlayer {
        override val currentPositionMillis: Long = 0
        override val isPlaying: Boolean = true
        override val currentItem: Any? get() = if (reportsItem) item else null
        val listeners = mutableListOf<ContentPlayer.Listener>()

        override fun addListener(listener: ContentPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: ContentPlayer.Listener) {
            listeners -= listener
        }
    }

    /** A playing Media3 player whose current media item the test sets, and the listener the adapter registered. */
    private class Media3 {
        var item: MediaItem? = MediaItem.fromUri("https://v.test/ep1.mp4")
        val listener = slot<Media3Player.Listener>()
        val player = mockk<Media3Player>(relaxed = true).also { player ->
            every { player.applicationLooper } returns Looper.getMainLooper()
            every { player.playWhenReady } returns true
            every { player.playbackState } returns Media3Player.STATE_READY
            every { player.playbackSuppressionReason } returns Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE
            every { player.contentPosition } returns 0
            every { player.currentMediaItem } answers { item }
            every { player.addListener(capture(listener)) } returns Unit
        }

        fun transition(to: MediaItem?, reason: Int) {
            item = to
            listener.captured.onMediaItemTransition(to, reason)
        }
    }

    private fun expectContentChanged(ad: VideoAd, container: FrameLayout, comment: String) {
        try {
            ad.show(container)
            fail("expected Invalidated(ContentChanged): $comment")
        } catch (error: VideoAdException.Invalidated) {
            assertEquals(comment, VideoAd.InvalidationReason.ContentChanged, error.reason)
        }
        assertEquals(comment, VideoAd.InvalidationReason.ContentChanged, ad.invalidationReason)
        assertFalse(comment, ad.wasShown)
        assertEquals(comment, 0, container.childCount)
    }

    @Test fun aPlayerThatChangesItsItemSilentlyEndsTheAdOnScreenAtTheNextTick() {
        val player = Player(item = "ep-1", reportsItem = true)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        player.item = "ep-2" // no listener call
        idle(so.kontext.video.internal.content.ContentPlayback.tickIntervalMillis * 2)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(null, so.kontext.video.internal.screen.ScreenSlot.current)
    }

    @Test fun aPlayerThatCannotTellItsItemForAMomentKeepsTheAdOnScreen() {
        val player = Player(item = "ep-1", reportsItem = true)
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = pause(owner)
        ad.listener = recorder
        ad.show(container())
        player.item = null // between items, or re-preparing the same one: it cannot tell
        idle(so.kontext.video.internal.content.ContentPlayback.tickIntervalMillis * 2)
        assertTrue("null is unknown, not another episode: ${recorder.reasons}", recorder.reasons.isEmpty())
        player.item = "ep-1"
        idle(so.kontext.video.internal.content.ContentPlayback.tickIntervalMillis * 2)
        assertTrue(recorder.reasons.isEmpty())
        player.item = "ep-2"
        idle(so.kontext.video.internal.content.ContentPlayback.tickIntervalMillis * 2)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aChangeOfTheOldPlayerNeverReachesAnAdShownFromItsItemRead() {
        // The old player reports no item at track; its first item read, made as its pause is delivered, is app
        // code that tracks a new player and shows an overlay over it. The old player's pause must not reach it.
        val owner = owner()
        val second = Player(item = "ep-b", reportsItem = true)
        val next = overlay(owner)
        val recorder = Recorder().also { next.listener = it }
        var item: Any? = null
        val first = object : ContentPlayer {
            val listeners = mutableListOf<ContentPlayer.Listener>()
            override val currentPositionMillis: Long = 0
            override val isPlaying: Boolean = true
            override val currentItem: Any?
                get() {
                    val read = item ?: return null
                    item = null // once
                    owner.track(second)
                    next.show(container())
                    return read
                }

            override fun addListener(listener: ContentPlayer.Listener) {
                listeners += listener
            }
        }
        owner.track(first)
        item = "ep-a"
        first.listeners.single().onIsPlayingChanged(false)
        assertTrue("the overlay is shown over the new player", next.wasShown)
        assertTrue("the old player's pause is not the new player's: ${recorder.reasons}", recorder.reasons.isEmpty())
    }

    @Test fun aTrackInsideTheFirstItemReadKeepsTheNewBindingsItem() {
        val second = Player(item = "ep-b", reportsItem = true)
        val owner = owner()
        var nested = true
        val first = object : ContentPlayer {
            override val currentPositionMillis: Long = 0
            override val isPlaying: Boolean = true
            override val currentItem: Any?
                get() {
                    if (nested) {
                        nested = false
                        owner.track(second) // the app's getter tracks another player
                    }
                    return "ep-a"
                }
        }
        owner.track(first)
        val ad = pause(owner)
        ad.show(container()) // B's own item is this episode's: showable
        assertTrue(ad.wasShown)
    }

    @Test fun aPlayerTrackedByAnotherVideoAdsMakesTheFirstOnesOverlayAndPauseAdThrow() {
        val player = Player()
        val episodeA = owner(Content("ep-a", "en"))
        episodeA.track(player)
        val overlay = overlay(episodeA, tracking = tracking("impression", "notUsed"))
        val pause = pause(episodeA)
        val episodeB = owner(Content("ep-b", "en"))
        episodeB.track(player)
        assertFalse("one VideoAds at a time", episodeA.playback.isTracking)
        assertTrue(episodeB.playback.isTracking)
        assertEquals("the first let its listener go", 1, player.listeners.size)
        val container = container()
        expectContentChanged(overlay, container, "the player plays episode B now")
        expectContentChanged(pause, container, "a pause ad too")
        assertTrue("no beacon", beacons.isEmpty())
        assertNull(ScreenSlot.current)
        // Episode A tracks a player again: its ads can be shown again.
        episodeA.track(player)
        assertFalse(episodeB.playback.isTracking)
        val again = overlay(episodeA)
        again.show(container)
        assertTrue(again.wasShown)
        again.dismiss()
    }

    @Test fun anOverlayOnScreenEndsInterruptedWhenAnotherVideoAdsTakesThePlayer() {
        val player = Player()
        val episodeA = owner(Content("ep-a2", "en"))
        episodeA.track(player)
        val overlay = overlay(episodeA)
        val recorder = Recorder()
        overlay.listener = recorder
        overlay.show(container())
        owner(Content("ep-b2", "en")).track(player)
        assertEquals("as for another item", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull(ScreenSlot.current)
    }

    @Test fun anItemReportedChangedOnTheSamePlayerMakesShowThrow() {
        val player = Player(item = "ep-1", reportsItem = true)
        val ads = owner()
        ads.track(player)
        val overlay = overlay(ads)
        player.item = "ep-2"
        expectContentChanged(overlay, container(), "the player reports another item than at track")
    }

    @Test fun withNoItemAtTrackTheFirstReportedIsTheEpisodes() {
        val player = Player(reportsItem = true)
        val ads = owner()
        ads.track(player)
        player.item = "ep-1"
        player.listeners.forEach { it.onContentChanged() }
        val first = overlay(ads)
        val container = container()
        first.show(container)
        assertTrue("the first item it got is the episode's", first.wasShown)
        first.dismiss()
        player.item = "ep-2"
        expectContentChanged(overlay(ads), container, "then another item")
    }

    @Test fun theSameItemShowsAsBefore() {
        val player = Player(item = "ep-1", reportsItem = true)
        val ads = owner()
        ads.track(player)
        val overlay = overlay(ads)
        idle(100)
        overlay.show(container())
        assertTrue(overlay.wasShown)
        assertNull(overlay.invalidationReason)
        overlay.dismiss()
    }

    @Test fun aPlayerWithoutAnItemReportIsCheckedOnlyForTheMove() {
        val player = Player(item = "ep-1")
        val ads = owner()
        ads.track(player)
        player.item = "ep-2"
        val overlay = overlay(ads)
        overlay.show(container())
        assertTrue("nothing can tell", overlay.wasShown)
        overlay.dismiss()
    }

    @Test fun aLinearAdIsNotChecked() {
        val player = Player()
        val episodeA = owner(Content("ep-linear", "en"))
        episodeA.track(player)
        val linear = linear(episodeA)
        owner(Content("ep-linear-b", "en")).track(player)
        linear.show(activity())
        assertTrue("a linear ad covers the content", linear.wasShown)
        assertNull(linear.invalidationReason)
    }

    @Test fun aMedia3PlayerOnAnotherItemOrItsNextRepeatMakesShowThrow() {
        val media3 = Media3()
        val ads = owner()
        ads.track(media3.player)
        val sameItem = overlay(ads)
        val container = container()
        sameItem.show(container)
        assertTrue(sameItem.wasShown)
        sameItem.dismiss()
        val nextEpisode = overlay(ads)
        media3.transition(MediaItem.fromUri("https://v.test/ep2.mp4"), Media3Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        expectContentChanged(nextEpisode, container, "the player's current media item is another")

        val looped = Media3()
        val loopAds = owner(Content("ep-loop", "en"))
        loopAds.track(looped.player)
        val overlay = overlay(loopAds)
        looped.transition(looped.item, Media3Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        expectContentChanged(overlay, container, "a repeat's next play is a new item")
    }

    @Test fun aMedia3PlayerTrackedByAnotherVideoAdsMakesTheFirstOnesAdsThrow() {
        val media3 = Media3()
        val episodeA = owner(Content("ep-m3a", "en"))
        episodeA.track(media3.player)
        val overlay = overlay(episodeA)
        owner(Content("ep-m3b", "en")).track(media3.player)
        assertFalse(episodeA.playback.isTracking)
        expectContentChanged(overlay, container(), "the same Media3 player plays episode B now")
    }
}
