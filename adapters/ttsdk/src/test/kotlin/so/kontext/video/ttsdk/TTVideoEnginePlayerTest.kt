package so.kontext.video.ttsdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.ContentPlayer

/** The adapter's rules, over an engine the test drives (TTSDK's own needs its native media stack). */
class TTVideoEnginePlayerTest {
    private class FakeEngine : Engine {
        override var isGone = false
        override var position = 0
        override var playbackState = 1
        override var videoId: String? = "vid-1"
        var events: Engine.Events? = null
        var listens = 0
        var stops = 0

        override fun listen(events: Engine.Events) {
            this.events = events
            listens += 1
        }

        override fun stopListening() {
            events = null
            stops += 1
        }
    }

    private val engine = FakeEngine()
    private val player = EnginePlayer(engine)

    /** An adapter whose callbacks wait in a queue, as a callback from another thread waits for the main one. */
    private val queued = mutableListOf<() -> Unit>()
    private val late = EnginePlayer(engine) { work -> queued += work }
    private val heard = mutableListOf<String>()
    private val listener = object : ContentPlayer.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            heard += "playing=$isPlaying"
        }

        override fun onContentChanged() {
            heard += "content"
        }
    }

    @Test fun playingIsThePlaybackStateAloneSoBufferingCountsAsPlaying() {
        assertTrue(player.isPlaying, "playing, whatever the load state")
        engine.playbackState = 2 // paused by the viewer
        assertFalse(player.isPlaying)
        engine.playbackState = 0 // stopped
        assertFalse(player.isPlaying)
    }

    @Test fun thePositionIsTheEnginesInMillisecondsNeverNegative() {
        engine.position = 12_345
        assertEquals(12_345L, player.currentPositionMillis)
        engine.position = -1 // not prepared yet
        assertEquals(0L, player.currentPositionMillis)
    }

    @Test fun aReleasedEngineIsNotPlayingAndHasNoItem() {
        engine.position = 5_000
        engine.isGone = true
        assertFalse(player.isPlaying)
        assertEquals(0L, player.currentPositionMillis)
        assertNull(player.currentItem)
    }

    @Test fun itListensOnceForAllListenersAndStopsWithTheLast() {
        val other = object : ContentPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        }
        player.addListener(listener)
        player.addListener(other)
        assertEquals(1, engine.listens)
        player.removeListener(listener)
        assertEquals(0, engine.stops)
        player.removeListener(other)
        assertEquals(1, engine.stops)
    }

    @Test fun aPauseAndAResumeAreEachHeardOnce() {
        player.addListener(listener)
        engine.playbackState = 2
        engine.events!!.playbackStateChanged(engine.playbackState)
        engine.playbackState = 1
        engine.events!!.playbackStateChanged(engine.playbackState)
        engine.events!!.playbackStateChanged(engine.playbackState) // no change
        assertEquals(listOf("playing=false", "playing=true"), heard)
    }

    @Test fun theEndOfTheVideoIsAnotherPlay() {
        player.addListener(listener)
        val before = player.currentItem
        engine.playbackState = 0
        engine.events!!.completion()
        assertEquals(listOf("content", "playing=false"), heard)
        assertNotEquals(before, player.currentItem)
    }

    @Test fun anotherVideoOnTheSameEngineChangesTheContent() {
        player.addListener(listener)
        engine.events!!.prepare() // the same vid again: nothing
        engine.videoId = "vid-2"
        engine.events!!.prepare()
        assertEquals(listOf("content"), heard)
        assertNotEquals(EnginePlayer(FakeEngine()).currentItem, player.currentItem)
    }

    @Test fun anEngineWithoutAVidHasNoItemAndItsFirstVidIsNotAChange() {
        engine.videoId = null
        player.addListener(listener)
        assertNull(player.currentItem)
        engine.videoId = "vid-1"
        engine.events!!.prepare()
        assertTrue(heard.isEmpty())
    }

    @Test fun theSpeedIsTheAppsWhenGiven() {
        assertNull(player.playbackSpeed, "unknown: TTSDK has no getter")
        player.speed = { 2f }
        assertEquals(2f, player.playbackSpeed)
    }

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeAt(0)()
    }

    @Test fun aPauseAndAResumeQueuedTogetherAreBothHeard() {
        late.addListener(listener)
        engine.playbackState = 2
        engine.events!!.playbackStateChanged(2)
        engine.playbackState = 1
        engine.events!!.playbackStateChanged(1)
        drain() // the main thread runs both once the engine plays again
        assertEquals(listOf("playing=false", "playing=true"), heard)
    }

    @Test fun workQueuedBeforeTheLastListenerLeftNeverReachesTheNextBinding() {
        late.addListener(listener)
        val old = engine.events!!
        old.completion()
        old.prepare()
        late.removeListener(listener)
        val next = mutableListOf<String>()
        late.addListener(object : ContentPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                next += "playing=$isPlaying"
            }

            override fun onContentChanged() {
                next += "content"
            }
        })
        val item = late.currentItem
        drain()
        assertTrue(next.isEmpty(), "the old subscription's completion: $next")
        assertEquals(item, late.currentItem, "not counted as another play of the new binding's video")
    }

    @Test fun theSpeedGoesWithTheLastListener() {
        player.addListener(listener)
        player.speed = { 2f }
        player.removeListener(listener)
        assertNull(player.speed, "no closure (and the Activity it holds) kept by the shared adapter")
        assertFalse(player.isFollowed)
    }

    @Test fun aTrackNestedInAnotherKeepsItsOwnSpeed() {
        val outer = object : ContentPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        }
        // B's track hands the engine over; the ad that ends there tracks it in C, whose binding is the newer.
        player.bind({ 1f }) { adapter ->
            adapter.addListener(outer)
            adapter.removeListener(outer)
            adapter.bind({ 8f }) { it.addListener(listener) }
        }
        assertEquals(8f, player.playbackSpeed, "C's speed, not the outer B call's")
        player.bind({ 2f }) { }
        assertEquals(2f, player.playbackSpeed, "a later track that keeps it followed sets its own")
    }
}
