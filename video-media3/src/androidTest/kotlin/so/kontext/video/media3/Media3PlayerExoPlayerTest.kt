package so.kontext.video.media3

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.VideoAdsPlayer
import java.util.Collections

/** The adapter over a real ExoPlayer playing a short local clip: what the SDK reads while content plays and pauses. */
@RunWith(AndroidJUnit4::class)
class Media3PlayerExoPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer
    private lateinit var adapter: Media3Player
    private val heard: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    private fun <T> main(read: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    /** Polls on the main thread until [condition] holds; false at the deadline. */
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return true
            SystemClock.sleep(50)
        }
        return main(condition)
    }

    /** The player's own view of things, for a failure message. */
    private fun state(): String = main {
        "state=${player.playbackState} playWhenReady=${player.playWhenReady} position=${player.currentPosition} " +
            "suppression=${player.playbackSuppressionReason} error=${player.playerError?.errorCodeName}: ${player.playerError?.cause}"
    }

    @Before fun makePlayer() {
        main {
            // The clip ships in this test APK's assets: the instrumentation's own context reads them.
            player = ExoPlayer.Builder(instrumentation.context).build()
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri("asset:///clip-6s.mp4"))
            player.prepare()
            adapter = Media3Player(player)
            adapter.addListener(VideoAdsPlayer.Listener { playing -> heard += playing })
        }
    }

    @After fun releasePlayer() {
        main { player.release() }
    }

    @Test fun playingAndPausedAndAPlayheadThatMovesOnlyWhilePlaying() {
        assertFalse("prepared, not asked to play", main { adapter.isPlaying })
        main { player.play() }
        assertTrue("plays: ${state()}", waitUntil { adapter.isPlaying && adapter.currentPositionMillis > 300 })
        assertEquals("told once: playing", listOf(true), heard.toList())
        val early = main { adapter.currentPositionMillis }
        assertTrue("the playhead advances while playing", waitUntil { adapter.currentPositionMillis >= early + 500 })

        main { player.pause() }
        assertTrue("paused", waitUntil { !adapter.isPlaying })
        assertEquals("told: paused", listOf(true, false), heard.toList())
        val held = main { adapter.currentPositionMillis }
        SystemClock.sleep(800)
        assertEquals("the playhead holds while paused", held, main { adapter.currentPositionMillis })

        main { player.play() }
        assertTrue("plays again", waitUntil { adapter.isPlaying && adapter.currentPositionMillis > held + 300 })
        assertEquals(listOf(true, false, true), heard.toList())
    }

    @Test fun theEndOfTheClipIsNoLongerPlaying() {
        main {
            player.seekTo(5_000)
            player.play()
        }
        assertTrue("ended: ${state()}", waitUntil { player.playbackState == androidx.media3.common.Player.STATE_ENDED })
        assertFalse(main { adapter.isPlaying })
        assertEquals("the last word is: not playing", false, heard.lastOrNull())
    }
}
