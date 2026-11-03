package so.kontext.video

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.playback.ContentPlayback

/**
 * An overlay over a tracked player whose content pauses as the app goes to the background (an app pausing its
 * player in `onStop`): it freezes, as its clock does, and goes on if the content plays again within the grace
 * after the return; still paused then, it ends INTERRUPTED. A pause in front, or under another screen of the
 * app, ends it at once, as in [ParityContentTest].
 */
@RunWith(RobolectricTestRunner::class)
class ContentInBackgroundTest : SdkTest() {
    /** A player whose state the test sets and reports to the SDK's listener. */
    private class Player : VideoAdsPlayer {
        var playing = true
        override val currentPositionMillis = 0L
        override val isPlaying: Boolean get() = playing
        val listeners = mutableListOf<VideoAdsPlayer.Listener>()

        override fun addListener(listener: VideoAdsPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: VideoAdsPlayer.Listener) {
            listeners -= listener
        }
    }

    private val tick get() = ContentPlayback.tickIntervalMillis

    /** A player screen the test drives through its lifecycle, and a container in its window, as `container()` makes one. */
    private fun playerScreen(): Pair<ActivityController<Activity>, FrameLayout> {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        view.layout(0, 0, 320, 400)
        idle()
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return controller to view
    }

    private fun Player.pauses() {
        playing = false
        listeners.toList().forEach { it.onIsPlayingChanged(false) }
    }

    private fun Player.plays() {
        playing = true
        listeners.toList().forEach { it.onIsPlayingChanged(true) }
    }

    @Test fun aPauseAsTheAppGoesToTheBackgroundFreezesTheOverlayAndItGoesOnWhenTheContentPlaysOnReturn() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, duration = "60000")
        ad.listener = recorder
        val (screen, container) = playerScreen()
        ad.show(container)
        val progress = ad.contentProgress
        // Home: the app pauses its player in onStop (at the lifecycle's ON_STOP), before the SDK hears the stop.
        screen.pause()
        player.pauses()
        screen.stop()
        idle(VideoAd.returnGraceMillis * 5)
        assertFalse("the background only freezes it, ticks included", ad.hasEnded)
        // Back: the app plays again as its screen starts.
        screen.restart().start()
        player.plays()
        screen.resume()
        idle(VideoAd.returnGraceMillis + tick * 2)
        assertTrue("it goes on, where it stopped", recorder.reasons.isEmpty())
        assertSame("its count of video time goes on", progress, ad.contentProgress)
        assertEquals(1, container.childCount)
        // Paused again in front: it ends at once, as before.
        player.pauses()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        screen.pause().stop().destroy()
    }

    @Test fun aPauseInTheBackgroundStillPausedAfterTheReturnAndTheGraceEndsTheOverlayInterrupted() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner)
        ad.listener = recorder
        val (screen, container) = playerScreen()
        ad.show(container)
        // A pause heard after the stop: in the background whatever the order.
        screen.pause().stop()
        player.pauses()
        idle(VideoAd.returnGraceMillis * 5)
        assertFalse(ad.hasEnded)
        screen.restart().start().resume()
        idle(VideoAd.returnGraceMillis - tick)
        assertFalse("the grace: the app may play it again", ad.hasEnded)
        idle(tick * 2)
        assertEquals("still paused: ended as any pause", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(0, container.childCount)
        assertNull(Sdk.adOnScreen)
        screen.pause().stop().destroy()
    }

    @Test fun aPauseInFrontOrUnderAnotherScreenOfTheAppEndsTheOverlayAtOnce() {
        for (covered in listOf(false, true)) {
            val player = Player()
            val owner = owner()
            owner.track(player)
            val recorder = Recorder()
            val ad = overlay(owner)
            ad.listener = recorder
            val (screen, container) = playerScreen()
            ad.show(container)
            // The app starts a screen of its own over the player and pauses the content as the player's stops.
            val cover = if (covered) Robolectric.buildActivity(Activity::class.java).also { screen.pause() }.setup() else null
            player.pauses()
            assertEquals("covered: $covered", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
            assertEquals(0, container.childCount)
            cover?.pause()?.stop()?.destroy()
            screen.destroy()
        }
    }
}
