package so.kontext.video.txplayer

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.rtmp.ITXVodPlayListener
import com.tencent.rtmp.TXLiveBase
import com.tencent.rtmp.TXVodPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.ContentPlayer
import so.kontext.video.KontextVideo
import so.kontext.video.VideoAds
import java.io.File
import java.util.Collections

/**
 * A real `TXVodPlayer`, tracked by a `VideoAds` with the app's events handed on through
 * [KontextTXVodPlayer.onPlayEvent]: what the SDK sees.
 *
 * The Player SDK plays nothing without a license (event -5, `VOD_PLAY_ERR_INVALID_LICENCE`, at `startVodPlay`), so
 * the tests that play the clip run only with one, given as instrumentation arguments (the license check may go
 * online):
 * `./gradlew :video-txplayer:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.txLicenceUrl=…
 * -Pandroid.testInstrumentationRunnerArguments.txLicenceKey=…`. Without one they are skipped, and the rest check
 * the wiring with the real classes: the refused start is heard as not playing, on the main thread.
 */
@RunWith(AndroidJUnit4::class)
class TXVodPlayerRealTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: TXVodPlayer
    private lateinit var ads: VideoAds
    private lateinit var adapter: VodPlayer
    private lateinit var clip: String
    private val events: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val offMain: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val heard: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Hears what the SDK hears, next to it. */
    private val recorder = object : ContentPlayer.Listener {
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

    private fun <T> main(read: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    private fun waitUntil(timeoutMillis: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return true
            SystemClock.sleep(50)
        }
        return main(condition)
    }

    private fun state(): String = main {
        "playing=${player.isPlaying} position=${player.currentPlaybackTime} duration=${player.duration} " +
            "events=${events.toList()} heard=${heard.toList()}"
    }

    @Before fun makePlayer() {
        val context = instrumentation.targetContext
        clip = File(context.cacheDir, "clip-6s.mp4").apply {
            instrumentation.context.assets.open("clip-6s.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }.absolutePath
        main {
            runCatching { KontextVideo.initialize(context, KontextVideo.Configuration("txplayer-test")) }
            player = TXVodPlayer(context)
            player.setMute(true)
            player.setAutoPlay(true)
            player.setVodListener(object : ITXVodPlayListener {
                override fun onPlayEvent(player: TXVodPlayer, event: Int, param: Bundle?) {
                    if (Looper.myLooper() != Looper.getMainLooper()) offMain += event
                    if (event != PLAY_PROGRESS) {
                        events += event
                        Log.i(TAG, "event $event ${param?.getString("EVT_MSG")}")
                    }
                    KontextTXVodPlayer.onPlayEvent(player, event)
                }

                override fun onNetStatus(player: TXVodPlayer, status: Bundle?) = Unit
            })
            ads = VideoAds(KontextVideo.Content("txplayer-episode", "en"))
            ads.track(player)
            adapter = adapterOf(player)
            assertTrue("the SDK follows the player", adapter.isFollowed)
            adapter.addListener(recorder)
        }
    }

    @After fun releasePlayer() {
        main {
            ads.destroy()
            player.stopPlay(true)
        }
    }

    private val licence: Pair<String, String>? = InstrumentationRegistry.getArguments().let { args ->
        val url = args.getString("txLicenceUrl")
        val key = args.getString("txLicenceKey")
        if (url.isNullOrEmpty() || key.isNullOrEmpty()) null else url to key
    }

    @Test fun withoutALicenceTheRefusedStartIsNotPlayingOnTheMainThread() {
        assumeTrue("a licence lets it play", licence == null)
        main { player.startVodPlay(clip) }
        assertTrue("refused: ${state()}", waitUntil { events.any { it < 0 } })
        assertEquals("told: not playing, ${state()}", listOf("playing=false"), heard.toList())
        assertFalse(main { adapter.isPlaying })
        assertEquals(0L, main { adapter.currentPositionMillis })
        assertNotNull("the player is the episode", main { adapter.currentItem })
        assertTrue("every event on the main thread: $offMain", offMain.isEmpty())
    }

    @Test fun anEventHandedOnFromAnotherThreadReachesTheSdkOnTheMainThread() {
        val onMain = Collections.synchronizedList(mutableListOf<Boolean>())
        main {
            adapter.addListener(object : ContentPlayer.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = Unit

                override fun onSeek() {
                    onMain += Looper.myLooper() == Looper.getMainLooper()
                }
            })
        }
        val thread = Thread { KontextTXVodPlayer.onPlayEvent(player, PlayEvent.SEEK_COMPLETE) }
        thread.start()
        thread.join()
        assertTrue("seek heard: ${heard.toList()}", waitUntil { "seek" in heard })
        assertEquals(listOf(true), onMain.toList())
    }

    @Test fun anotherVideoAdsTakesThePlayerAndDestroyLetsGo() {
        val other = main { VideoAds(KontextVideo.Content("txplayer-episode-2", "en")) }
        main {
            adapter.removeListener(recorder)
            other.track(player, currentItem = { "episode-2" })
        }
        assertTrue("one adapter per player", main { adapterOf(player) === adapter })
        assertTrue("the second one follows it", main { adapter.isFollowed })
        main { ads.destroy() }
        assertTrue("the first one had let go already", main { adapter.isFollowed })
        main { other.destroy() }
        assertFalse("nobody follows it", main { adapter.isFollowed })
        assertEquals("nothing of the app's is kept", null, main { adapter.item })
    }

    private fun start() {
        val (url, key) = licence ?: run {
            assumeTrue("plays only with a Player SDK licence: txLicenceUrl and txLicenceKey", false)
            return
        }
        main {
            TXLiveBase.getInstance().setLicence(instrumentation.targetContext, url, key)
            player.startVodPlay(clip)
        }
        assertTrue("began: ${state()}", waitUntil { PLAY_BEGIN in events })
    }

    @Test fun playsPausesAndResumesWithAPlayheadThatMoves() {
        start()
        assertTrue("plays: ${state()}", waitUntil { adapter.isPlaying && adapter.currentPositionMillis > 300 })
        assertTrue("told: playing, ${state()}", "playing=true" in heard)
        val early = main { adapter.currentPositionMillis }
        assertTrue("the playhead advances: ${state()}", waitUntil { adapter.currentPositionMillis >= early + 500 })

        main { player.pause() }
        assertFalse("paused: ${state()}", main { adapter.isPlaying })
        SystemClock.sleep(200)
        val held = main { adapter.currentPositionMillis }
        SystemClock.sleep(1_000)
        val later = main { adapter.currentPositionMillis }
        assertTrue("the playhead holds while paused: $held, then $later", later in held..held + 100)

        main { player.resume() }
        assertTrue("plays again: ${state()}", waitUntil { adapter.isPlaying && adapter.currentPositionMillis > held + 300 })
        assertTrue("every event on the main thread: $offMain", offMain.isEmpty())
    }

    @Test fun aSeekIsHeard() {
        start()
        assertTrue(waitUntil { adapter.currentPositionMillis > 300 })
        heard.clear()
        main { player.seek(3.0f) }
        assertTrue("seek heard: ${state()}", waitUntil { "seek" in heard })
        assertTrue("at the new position: ${state()}", waitUntil { adapter.currentPositionMillis >= 2_900 })
        assertTrue("still playing: ${state()}", main { adapter.isPlaying })
    }

    @Test fun theEndIsAnotherPlayAndNotPlaying() {
        start()
        val first = main { adapter.currentItem }
        assertNotNull(first)
        main { player.seek(4.5f) }
        assertTrue("ended: ${state()}", waitUntil(30_000) { PLAY_END in events })
        assertFalse("not playing at the end: ${state()}", main { adapter.isPlaying })
        assertEquals("content, then not playing: ${state()}", listOf("content", "playing=false"), heard.filter { it == "content" || it == "playing=false" }.takeLast(2))
        assertNotEquals("another play", first, main { adapter.currentItem })
        assertTrue("every event on the main thread: $offMain", offMain.isEmpty())
    }

    @Test fun aLoopsNextRoundIsAnotherPlayAndStillPlaying() {
        main { player.setLoop(true) }
        start()
        val first = main { adapter.currentItem }
        main { player.seek(4.5f) }
        assertTrue("looped: ${state()}", waitUntil(30_000) { LOOP_ONCE_COMPLETE in events })
        assertTrue("content changed: ${state()}", waitUntil { "content" in heard })
        assertFalse("no end on a loop: ${state()}", PLAY_END in events)
        assertNotEquals("another play", first, main { adapter.currentItem })
        assertTrue("plays on: ${state()}", waitUntil { adapter.isPlaying })
        assertTrue("every event on the main thread: $offMain", offMain.isEmpty())
    }

    private companion object {
        const val TAG = "TXVodPlayerRealTest"
        const val PLAY_BEGIN = 2004
        const val PLAY_PROGRESS = 2005
        const val PLAY_END = 2006
        const val LOOP_ONCE_COMPLETE = 6001

        /** The adapter `track` made for the player: the one the app's events reach. */
        fun adapterOf(player: TXVodPlayer): VodPlayer {
            val field = Class.forName("so.kontext.video.txplayer.TXVodPlayers").getDeclaredField("adapters")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (field.get(null) as Map<TXVodPlayer, VodPlayer>).getValue(player)
        }
    }
}
