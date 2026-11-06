package so.kontext.video

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.content.ContentPlayback
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot

/**
 * The app's code runs inside the SDK's: a listener or a player read may end the ad on the spot, and whatever
 * the SDK was about to do next must then not happen. And what arrives after the end changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ReentryTest : SdkTest() {
    private class Player(var playing: Boolean = true, var position: Long = 0) : ContentPlayer {
        var onRead: (() -> Unit)? = null
        override val currentPositionMillis: Long get() = position.also { onRead?.invoke() }
        override val isPlaying get() = playing
        val listeners = mutableListOf<ContentPlayer.Listener>()
        override fun addListener(listener: ContentPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: ContentPlayer.Listener) {
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
        assertNull("the slot is free", ScreenSlot.current)
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
        val clock = ad.onScreenClock!!
        clock.windowVisibilityChanged(isVisible = true) // already counting: nothing changes
        now = 1_000
        player.position = 1_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        clock.windowVisibilityChanged(isVisible = false)
        // Away: the content plays on, picture-in-picture, without the overlay on screen.
        player.position = 60_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("nothing counts while the window is hidden", 1_000, ad.contentClock!!.elapsedMillis)
        clock.windowVisibilityChanged(isVisible = true)
        now = 2_000
        player.position = 61_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("counting resumes from where the content is", 2_000, ad.contentClock!!.elapsedMillis)
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
        val clock = OnScreenTimeClock(1_000, "ad-1") { elapsed += 1 }
        clock.windowVisibilityChanged(isVisible = true) // before start: ignored
        assertFalse(clock.isCounting)
        clock.start(isVisible = false)
        clock.start(isVisible = true) // a second start: ignored
        assertFalse(clock.isCounting)
        var activations = 0
        clock.onActivate = { activations += 1 }
        clock.windowVisibilityChanged(isVisible = true)
        assertEquals(1, activations)
        now = 400
        clock.windowVisibilityChanged(isVisible = false)
        clock.windowVisibilityChanged(isVisible = false)
        now = 10_000
        assertEquals(400, clock.elapsedMillis)
        clock.windowVisibilityChanged(isVisible = true)
        now = 10_600
        idle(600)
        assertEquals("the remaining 600 ms ran out once", 1, elapsed)
        assertEquals(1_000, clock.stop())
        assertEquals("a second stop returns the same", 1_000, clock.stop())
        clock.windowVisibilityChanged(isVisible = true)
        assertFalse("stopped for good", clock.isCounting)
    }

    @Test fun aStoppedClockNeverElapses() {
        val clock = OnScreenTimeClock(500, "ad-1") { throw AssertionError("elapsed after stop") }
        clock.start(isVisible = true)
        clock.stop()
        idle(1_000)
        val endless = OnScreenTimeClock(null, "ad-2") { throw AssertionError("no duration, no end") }
        endless.start(isVisible = true)
        idle(100_000)
        assertTrue(endless.isCounting)
    }

    @Test fun aShowWhoseAppCodeShowsTheSameAdReturnsAccepted() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val overlay = overlay(owner)
        val pauseAd = pause(owner)
        val target = container()
        overlay.listener = object : VideoAdListener {
            // The usual pause flow: the overlay ended, its listener shows the pause ad.
            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = pauseAd.show(target)
        }
        overlay.show(container())
        player.playing = false // no listener call: the next show ends the overlay
        val recorder = Recorder()
        pauseAd.listener = recorder
        pauseAd.show(target) // the overlay's onAdEnded showed it: accepted, not AlreadyShown
        assertTrue(pauseAd.wasShown)
        assertTrue(ScreenSlot.current === pauseAd)
        pauseAd.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
    }

    @Test fun aShowOfAnAdAlreadyShownStillThrows() {
        val ad = overlay()
        ad.show(container())
        assertThrows(VideoAdException.AlreadyShown::class.java) { ad.show(container()) }
    }

    @Test fun aContainerGetterThatRejectsTheAdMakesShowThrowRejected() {
        val ad = overlay()
        val target = hookedContainer { ad.reject() }
        val error = assertThrows(VideoAdException.Invalidated::class.java) { ad.show(target) }
        assertEquals(VideoAd.InvalidationReason.Rejected, error.reason)
        assertNull(ScreenSlot.current)
        assertFalse(ad.wasShown)
    }

    @Test fun aContainerGetterThatShowsAnotherAdMakesShowThrowAnotherAdShowing() {
        val owner = owner()
        val ad = overlay(owner)
        val other = pause(owner)
        val elsewhere = container()
        val target = hookedContainer { other.show(elsewhere) }
        assertThrows(VideoAdException.AnotherAdShowing::class.java) { ad.show(target) }
        assertTrue("the other ad keeps the slot", ScreenSlot.current === other)
        assertFalse(ad.wasShown)
        assertTrue(ad.isReady)
        other.dismiss()
        assertNull(ScreenSlot.current)
    }

    @Test fun aContainerGetterThatDestroysTheOwnerMakesShowThrowDestroyed() {
        val owner = owner()
        val ad = overlay(owner)
        val target = hookedContainer { owner.destroy() }
        assertThrows(VideoAdException.Destroyed::class.java) { ad.show(target) }
        assertNull(ScreenSlot.current)
    }

    @Test fun anActivityGetterThatDestroysTheOwnerMakesALinearShowThrowDestroyed() {
        val owner = owner()
        val ad = linear(owner)
        HookedActivity.hook = { owner.destroy() }
        val activity = Robolectric.buildActivity(HookedActivity::class.java).setup().get()
        try {
            assertThrows(VideoAdException.Destroyed::class.java) { ad.show(activity) }
        } finally {
            HookedActivity.hook = null
        }
        assertNull(ScreenSlot.current)
    }

    /** A container whose `getAlpha`, which show's checks read, runs [hook] once: the app's code inside show. */
    private fun hookedContainer(hook: () -> Unit): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var pending: (() -> Unit)? = null
        val view = object : FrameLayout(activity) {
            override fun getAlpha(): Float {
                pending?.let {
                    pending = null
                    it()
                }
                return super.getAlpha()
            }
        }
        activity.setContentView(view)
        view.layout(0, 0, 320, 400)
        idle()
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        pending = hook
        return view
    }

    /** An Activity whose `isFinishing`, which a linear show's checks read, runs [hook] once. */
    class HookedActivity : Activity() {
        override fun isFinishing(): Boolean {
            hook?.let {
                hook = null
                it()
            }
            return super.isFinishing()
        }

        companion object {
            var hook: (() -> Unit)? = null
        }
    }
}
