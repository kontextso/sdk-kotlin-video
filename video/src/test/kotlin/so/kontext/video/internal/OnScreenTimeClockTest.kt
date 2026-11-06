package so.kontext.video.internal

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.platform.Environment
import java.time.Duration

/** The time an ad is seen on screen: counted only while visible and seen, its duration reached once. */
@RunWith(RobolectricTestRunner::class)
class OnScreenTimeClockTest {
    @After fun restore() {
        OnScreenTimeClock.longestWaitMillis = 86_400_000L
        Environment.reset()
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test fun aDurationLongerThanTheLongestWaitWaitsAgainForTheRest() {
        Environment.now = { android.os.SystemClock.uptimeMillis() }
        OnScreenTimeClock.longestWaitMillis = 100
        var elapsed = 0
        val clock = OnScreenTimeClock(350, "ad-1") { elapsed += 1 }
        clock.start(isVisible = true)
        idle(150)
        assertEquals("a capped wait does not end it early", 0, elapsed)
        idle(150)
        assertEquals(0, elapsed)
        idle(100)
        assertEquals("ended once, at its duration", 1, elapsed)
        assertTrue(clock.elapsedMillis >= 350)
    }

    @Test fun itCountsOnlyWhileVisibleAndSeen() {
        var now = 0L
        Environment.now = { now }
        val clock = OnScreenTimeClock(null, "ad-1") {}
        clock.start(isVisible = true, isSeen = true)
        now = 300
        clock.seenChanged(false)
        now = 1_000
        assertEquals("unseen time does not count", 300, clock.elapsedMillis)
        clock.seenChanged(true)
        now = 1_200
        clock.windowVisibilityChanged(isVisible = false)
        now = 5_000
        assertEquals(500, clock.elapsedMillis)
        assertFalse(clock.isCounting)
        clock.windowVisibilityChanged(isVisible = true)
        now = 5_100
        assertEquals(600, clock.stop())
        now = 9_000
        assertEquals("stopped for good", 600, clock.stop())
    }
}
