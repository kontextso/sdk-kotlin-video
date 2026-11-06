package so.kontext.video.internal.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException

/** Whether `show` may go on, as a table: the facts → the first refusal, in the order the checks run. */
class ShowCheckTest {
    private data class Facts(
        val wasShown: Boolean = false,
        val usableError: VideoAdException? = null,
        val isAnotherAdOnScreen: Boolean = false,
        val isLoaded: Boolean = true,
        val isContentPaused: Boolean = false,
    )

    private fun refusal(facts: Facts): String =
        ShowCheck.refusal(facts.wasShown, facts.usableError, facts.isAnotherAdOnScreen, facts.isLoaded, facts.isContentPaused)
            ?.let { it::class.simpleName } ?: "none"

    @Test fun theFirstRefusalWins() {
        val expired = VideoAdException.Invalidated(VideoAd.InvalidationReason.Expired)
        val table = listOf(
            Facts() to "none",
            Facts(wasShown = true, usableError = expired, isAnotherAdOnScreen = true, isLoaded = false, isContentPaused = true) to "AlreadyShown",
            Facts(usableError = expired, isAnotherAdOnScreen = true, isLoaded = false, isContentPaused = true) to "Invalidated",
            Facts(usableError = VideoAdException.Destroyed()) to "Destroyed",
            Facts(isAnotherAdOnScreen = true, isLoaded = false, isContentPaused = true) to "AnotherAdShowing",
            Facts(isLoaded = false, isContentPaused = true) to "NotLoaded",
            Facts(isContentPaused = true) to "ContentPaused",
        )
        for ((facts, expected) in table) assertEquals(expected, refusal(facts), facts.toString())
    }
}
