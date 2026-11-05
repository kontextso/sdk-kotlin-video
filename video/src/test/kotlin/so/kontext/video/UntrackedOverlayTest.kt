package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.support.Logger

/** An overlay shown before `track`: it runs on its clock, as before, and says so at show. */
@RunWith(RobolectricTestRunner::class)
class UntrackedOverlayTest : SdkTest() {
    private class Player : ContentPlayer {
        override val currentPositionMillis: Long = 0
        override val isPlaying: Boolean = true
    }

    private val warnings = mutableListOf<String>()

    @Test fun anOverlayShownBeforeTrackSaysItsDurationCountsOnScreenTime() {
        Logger.sink = { level, message -> if (level == KontextVideo.Configuration.LogLevel.WARNING) warnings += message }
        val ad = overlay(duration = "5")
        ad.show(container())
        val expected = "overlay ${ad.id.take(8)} shown before track(): its duration counts on-screen time, not video time"
        assertEquals(listOf(expected), warnings.filter { "before track()" in it })
        assertTrue("shown as before, on its clock", ad.wasShown && !ad.hasEnded && ad.clock != null)
    }

    @Test fun aTrackedOverlayDoesNotWarn() {
        Logger.sink = { level, message -> if (level == KontextVideo.Configuration.LogLevel.WARNING) warnings += message }
        val owner = owner()
        owner.track(Player())
        val ad = overlay(owner, duration = "5")
        ad.show(container())
        assertTrue(ad.wasShown && !ad.hasEnded)
        assertTrue("tracked: no warning", warnings.none { "before track()" in it })
    }
}
