package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.internal.frame.TouchGate
import so.kontext.video.internal.frame.TouchGate.Companion.TOUCH_WINDOW_MILLIS

/** One touch opens one URL within its window, on any clock: one that starts at 0, or far along. */
class TouchGateTest {
    @Test fun aTouchOpensOneUrlWithinItsWindow() {
        // touch at, URL asked at → opens
        val table = listOf(
            Triple(1_000L, 1_000L, true),
            Triple(1_000L, 1_000L + TOUCH_WINDOW_MILLIS, true),
            Triple(1_000L, 1_001L + TOUCH_WINDOW_MILLIS, false),
            Triple(0L, 0L, true),
            Triple(0L, TOUCH_WINDOW_MILLIS + 1, false),
            Triple(1_700_000_000_000L, 1_700_000_000_500L, true),
        )
        for ((touch, ask, expected) in table) {
            val gate = TouchGate()
            gate.noteTouch(touch)
            assertEquals(expected, gate.consume(ask), "touch at $touch, asked at $ask")
        }
    }

    @Test fun withoutATouchNothingOpensEvenOnAClockThatStartsAtZero() {
        for (now in listOf(0L, 1L, TOUCH_WINDOW_MILLIS, -TOUCH_WINDOW_MILLIS, 1_700_000_000_000L)) {
            assertEquals(false, TouchGate().consume(now), "at $now")
        }
    }

    @Test fun aTouchIsSpentByItsFirstUrlAndANewOneRenewsIt() {
        val gate = TouchGate()
        gate.noteTouch(5_000)
        assertEquals(true, gate.consume(5_100))
        assertEquals(false, gate.consume(5_100), "spent")
        gate.noteTouch(5_200)
        assertEquals(true, gate.consume(6_200), "a new touch, its own window")
    }

    @Test fun aLaterTouchMovesTheWindow() {
        val gate = TouchGate()
        gate.noteTouch(1_000)
        gate.noteTouch(3_000)
        assertEquals(true, gate.consume(3_000 + TOUCH_WINDOW_MILLIS))
    }
}
