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

    @Test fun aDurationOfZeroOrLessElapsesAtOnceAndOnce() {
        Environment.now = { android.os.SystemClock.uptimeMillis() }
        for (duration in listOf(0L, -5_000L)) {
            var elapsed = 0
            val clock = OnScreenTimeClock(duration, "ad-1") { elapsed += 1 }
            clock.start(isVisible = true)
            idle(0)
            clock.windowVisibilityChanged(isVisible = false)
            clock.windowVisibilityChanged(isVisible = true)
            idle(1_000)
            assertEquals("duration $duration", 1, elapsed)
        }
    }

    @Test fun aPausedClockWaitsOnlyForTheTimeStillMissing() {
        Environment.now = { android.os.SystemClock.uptimeMillis() }
        var elapsed = 0
        val clock = OnScreenTimeClock(1_000, "ad-1") { elapsed += 1 }
        clock.start(isVisible = true)
        idle(400)
        clock.windowVisibilityChanged(isVisible = false)
        idle(10_000)
        assertEquals("time in the background does not count", 0, elapsed)
        clock.windowVisibilityChanged(isVisible = true)
        clock.seenChanged(false)
        idle(10_000)
        assertEquals("nor time unseen", 0, elapsed)
        clock.seenChanged(true)
        idle(599)
        assertEquals(0, elapsed)
        idle(1)
        assertEquals(1, elapsed)
        assertEquals(1_000, clock.elapsedMillis)
    }

    @Test fun aStoppedClockNeverElapsesAndIgnoresWhatFollows() {
        Environment.now = { android.os.SystemClock.uptimeMillis() }
        var elapsed = 0
        var activations = 0
        val clock = OnScreenTimeClock(1_000, "ad-1") { elapsed += 1 }
        clock.onActivate = { activations += 1 }
        clock.start(isVisible = true)
        idle(300)
        assertEquals(300, clock.stop())
        clock.windowVisibilityChanged(isVisible = false)
        clock.windowVisibilityChanged(isVisible = true)
        clock.seenChanged(true)
        clock.start(isVisible = true)
        idle(5_000)
        assertEquals(0, elapsed)
        assertEquals(0, activations)
        assertFalse(clock.isCounting)
        assertEquals(300, clock.elapsedMillis)
    }

    @Test fun beforeItStartsNothingCountsAndAStartIsTakenOnce() {
        var now = 0L
        Environment.now = { now }
        var activations = 0
        val clock = OnScreenTimeClock(null, "ad-1") {}
        clock.onActivate = { activations += 1 }
        clock.windowVisibilityChanged(isVisible = true)
        clock.seenChanged(true)
        now = 500
        assertEquals(0, clock.elapsedMillis)
        assertEquals("a window change before the start is no return", 0, activations)
        clock.start(isVisible = false)
        clock.start(isVisible = true)
        now = 1_000
        assertFalse("the second start changed nothing", clock.isCounting)
        assertEquals(0, clock.elapsedMillis)
        clock.windowVisibilityChanged(isVisible = true)
        assertEquals("the window came back", 1, activations)
        clock.windowVisibilityChanged(isVisible = true)
        assertEquals("already visible: no second return", 1, activations)
        now = 1_250
        assertEquals(250, clock.elapsedMillis)
    }

    @Test fun startedUnseenItCountsFromTheFirstSight() {
        var now = 0L
        Environment.now = { now }
        val clock = OnScreenTimeClock(null, "ad-1") {}
        clock.start(isVisible = true, isSeen = false)
        now = 700
        assertFalse(clock.isCounting)
        clock.seenChanged(true)
        now = 900
        assertEquals(200, clock.stop())
    }

    @Test fun theClockCountsOnlyWhileTheWindowShowsAndTellsWhenItComesBack() {
        var now = 0L
        Environment.now = { now }
        var elapsed = false
        var activations = 0
        val clock = OnScreenTimeClock(durationMillis = 3_000, adId = "ad-1") { elapsed = true }
        clock.onActivate = { activations++ }
        clock.start(isVisible = true)
        now = 1_000
        clock.windowVisibilityChanged(false)
        assertFalse(clock.isCounting)
        now = 50_000 // hidden time counts nothing
        clock.windowVisibilityChanged(true)
        assertEquals(1, activations)
        now = 51_500
        assertEquals(2_500, clock.elapsedMillis)
        assertEquals(2_500, clock.stop())
        clock.windowVisibilityChanged(true) // stopped: nothing
        assertFalse(elapsed)
    }
}
