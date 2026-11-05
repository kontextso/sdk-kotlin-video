package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.screen.ScreenSlot
import so.kontext.video.internal.screen.VideoAdSlot

/** The declarative slot behind `VideoAdHost`: the ad it is given is shown, replaced or dismissed. */
@RunWith(RobolectricTestRunner::class)
class VideoAdSlotTest : SdkTest() {
    private val recorder = Recorder()
    private val failures = mutableListOf<VideoAdException>()

    private fun slot() = VideoAdSlot(container()).also { slot ->
        slot.listener = recorder
        slot.onShowFailed = { _, error -> failures += error }
    }

    @Test fun anAdGivenIsShownInTheContainerAndHeard() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        assertTrue(ad.wasShown)
        assertSame(ad, slot.ad)
        page(ad, "ad.close")
        assertEquals(listOf(VideoAd.EndReason.CLOSED), recorder.reasons)
        assertNull("an ended ad leaves the slot", slot.ad)
    }

    @Test fun nullDismissesTheAdShown() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        slot.set(null)
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertNull(ScreenSlot.current)
    }

    @Test fun anotherAdReplacesTheOneShown() {
        val slot = slot()
        val first = pause()
        val second = pause()
        slot.set(first)
        laidOut()
        slot.set(second)
        laidOut()
        assertEquals("the first was dismissed", listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertTrue(second.wasShown)
        assertSame(second, slot.ad)
    }

    @Test fun theSameAdAgainDoesNothing() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        slot.set(ad)
        laidOut()
        assertTrue(recorder.reasons.isEmpty())
        assertSame(ad, slot.ad)
    }

    @Test fun anAdThatCannotBeShownIsReportedAndNotHeld() {
        val elsewhere = pause()
        elsewhere.show(container()) // the app's one slot is taken
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        assertTrue(failures.single() is VideoAdException.AnotherAdShowing)
        assertNull(slot.ad)
        assertFalse(ad.wasShown)
        assertNull("the ad's listener is not left on the slot", ad.listener)
    }

    @Test fun anAdShownElsewhereIsLeftAlone() {
        val ad = pause()
        ad.show(container())
        val slot = slot()
        slot.set(ad)
        slot.set(null)
        assertFalse("not dismissed by a slot that did not show it", ad.hasEnded)
        assertNull(slot.ad)
    }

    @Test fun anAdTakenAwayBeforeLayoutIsNeverShown() {
        val slot = VideoAdSlot(android.widget.FrameLayout(activity())) // not laid out yet
        val ad = pause()
        slot.set(ad)
        slot.set(null)
        laidOut()
        assertFalse(ad.wasShown)
        assertNull("its listener is given back", ad.listener)
    }

    @Test fun aLinearAdStartsFromTheContainersActivityAndIsNotDismissed() {
        val slot = slot()
        val ad = linear()
        slot.set(ad)
        laidOut()
        assertTrue(ad.wasShown)
        slot.set(null)
        assertFalse("a linear ad ends by itself or by the viewer", ad.hasEnded)
        ad.end(VideoAd.EndReason.COMPLETED)
        assertEquals("its end still reaches the app", listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
    }
}
