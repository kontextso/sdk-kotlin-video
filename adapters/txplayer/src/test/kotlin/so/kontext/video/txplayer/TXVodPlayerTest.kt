package so.kontext.video.txplayer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.ContentPlayer

/** The adapter's rules, over a player the test drives (the Player SDK's own needs its native media stack). */
class TXVodPlayerTest {
    private class FakeVod : Vod {
        override var isGone = false
        override var positionSeconds = 0f
        override var isPlaying = true
    }

    private val vod = FakeVod()
    private val player = VodPlayer(vod)
    private val heard = mutableListOf<String>()
    private val listener = object : ContentPlayer.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            heard += "playing=$isPlaying"
        }

        override fun onSeek() {
            heard += "seek"
        }

        override fun onContentChanged() {
            heard += "content"
        }
    }

    @Test fun playingIsThePlayersOwnSoAStallToLoadStillCounts() {
        assertTrue(player.isPlaying)
        player.addListener(listener)
        player.playEvent(2007) // loading: the player still says it plays
        assertTrue(player.isPlaying)
        assertTrue(heard.isEmpty(), "loading is no change: $heard")
        vod.isPlaying = false // paused by the viewer
        assertFalse(player.isPlaying)
    }

    @Test fun thePositionIsInMillisecondsNeverNegativeOrNotANumber() {
        vod.positionSeconds = 12.5f
        assertEquals(12_500L, player.currentPositionMillis)
        vod.positionSeconds = -1f
        assertEquals(0L, player.currentPositionMillis)
        vod.positionSeconds = Float.NaN
        assertEquals(0L, player.currentPositionMillis)
    }

    @Test fun aCollectedPlayerIsNotPlayingAndHasNoItem() {
        vod.positionSeconds = 5f
        vod.isGone = true
        assertFalse(player.isPlaying)
        assertEquals(0L, player.currentPositionMillis)
        assertNull(player.currentItem)
    }

    @Test fun beginAndLoadingEndTellThePlayersStateNow() {
        player.addListener(listener)
        player.playEvent(PlayEvent.PLAY_BEGIN)
        vod.isPlaying = false // the app's own handler paused a page prepared ahead
        player.playEvent(PlayEvent.PLAY_BEGIN)
        vod.isPlaying = true
        player.playEvent(PlayEvent.LOADING_END)
        assertEquals(listOf("playing=true", "playing=false", "playing=true"), heard)
    }

    @Test fun theEndIsAnotherPlayAndNotPlaying() {
        player.addListener(listener)
        val before = player.currentItem
        vod.isPlaying = false
        player.playEvent(PlayEvent.PLAY_END)
        assertEquals(listOf("content", "playing=false"), heard)
        assertNotEquals(before, player.currentItem)
    }

    @Test fun aLoopsNextRoundIsAnotherPlayThatGoesOnPlaying() {
        player.addListener(listener)
        val before = player.currentItem
        player.playEvent(PlayEvent.LOOP_ONCE_COMPLETE)
        assertEquals(listOf("content"), heard)
        assertNotEquals(before, player.currentItem)
    }

    @Test fun aSeekIsHeard() {
        player.addListener(listener)
        player.playEvent(PlayEvent.SEEK_COMPLETE)
        assertEquals(listOf("seek"), heard)
    }

    @Test fun anErrorTellsThePlayersState() {
        player.addListener(listener)
        vod.isPlaying = false
        player.playEvent(-2301)
        assertEquals(listOf("playing=false"), heard)
    }

    @Test fun otherEventsSayNothing() {
        player.addListener(listener)
        listOf(2003, 2005, 2007, 2009, 2013, 2026).forEach(player::playEvent)
        assertTrue(heard.isEmpty(), "$heard")
    }

    @Test fun withoutTheAppsItemThePlayerIsTheEpisode() {
        val first = player.currentItem
        assertNotNull(first)
        assertEquals(first, player.currentItem, "the same play reads the same")
    }

    @Test fun theAppsItemNamesTheEpisodeAndItsNullIsCannotTell() {
        var episode: String? = "ep-1"
        player.bind({ episode }, null) { it.addListener(listener) }
        val first = player.currentItem
        episode = "ep-2"
        assertNotEquals(first, player.currentItem)
        episode = null
        assertNull(player.currentItem)
    }

    @Test fun anItemThatThrowsIsCannotTell() {
        player.bind({ error("app") }, null) { it.addListener(listener) }
        assertNull(player.currentItem)
    }

    @Test fun theSpeedIsTheAppsWhenGiven() {
        assertNull(player.playbackSpeed, "unknown: the Player SDK has no getter")
        player.bind(null, { 2f }) { it.addListener(listener) }
        assertEquals(2f, player.playbackSpeed)
    }

    @Test fun theClosuresGoWithTheLastListener() {
        player.bind({ "ep-1" }, { 2f }) { it.addListener(listener) }
        player.removeListener(listener)
        assertNull(player.speed, "no closure (and the Activity it holds) kept by the shared adapter")
        assertNull(player.item)
        assertFalse(player.isFollowed)
    }

    @Test fun trackingAgainKeepsTheNewItemThroughTheSdksLetGo() {
        player.bind({ "ep-1" }, null) { it.addListener(listener) }
        var seenInTrack: Any? = null
        // The SDK's track of the same VideoAds: its old listener leaves, the new one comes, the item is read.
        player.bind({ "ep-2" }, null) {
            it.removeListener(listener)
            it.addListener(listener)
            seenInTrack = it.currentItem
        }
        assertEquals(player.currentItem, seenInTrack)
        assertNotNull(player.item, "the new binding's item stays")
    }

    @Test fun aRefusedTrackKeepsNothing() {
        player.bind({ "ep-1" }, { 2f }) { }
        assertNull(player.item)
        assertNull(player.speed)
    }

    @Test fun aTrackNestedInAnotherKeepsItsOwn() {
        val outer = object : ContentPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        }
        // B's track hands the player over; the ad that ends there tracks it in C, whose binding is the newer.
        player.bind({ "b" }, { 1f }) { adapter ->
            adapter.addListener(outer)
            adapter.removeListener(outer)
            adapter.bind({ "c" }, { 8f }) { it.addListener(listener) }
        }
        assertEquals(8f, player.playbackSpeed, "C's speed, not the outer B call's")
        assertEquals("c", player.item?.invoke())
    }

    @Test fun eventsWithNobodyFollowingReachNobody() {
        player.playEvent(PlayEvent.PLAY_END)
        player.addListener(listener)
        assertTrue(heard.isEmpty())
    }
}
