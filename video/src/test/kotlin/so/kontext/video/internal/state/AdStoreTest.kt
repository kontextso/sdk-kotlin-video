package so.kontext.video.internal.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.AdFormat
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.state.AdStore.Step

/** How an answer is followed for one format, as a table: what is held × what the answer says → the steps. */
class AdStoreTest {
    private val pause = AdFormat.PAUSE

    private fun payload(id: String): AdPayload = AdPayload.parse(Fixtures.json(Fixtures.pause(id = id)))

    private fun answer(vararg results: AdsResponse.FormatResult) = AdsResponse.Formats(results.toList())

    private fun ad(id: String) = AdsResponse.FormatResult(pause, listOf(payload(id)), null)

    private fun noFill(reason: NoFillReason) = AdsResponse.FormatResult(pause, emptyList(), reason)

    /** The steps for the pause format only: the other formats are left out of every answer here. */
    private fun steps(held: String?, response: AdsResponse, spent: Set<String> = emptySet()): List<String> =
        AdStore.plan(response, listOfNotNull(held?.let { pause to it }).toMap(), spent).filter { it.format == pause }.map(::describe)

    private fun describe(step: Step): String = when (step) {
        is Step.Keep -> "Keep"
        is Step.Drop -> "Drop"
        is Step.Replace -> "Replace(${step.payload.id})"
        is Step.NoFill -> "NoFill(${step.reason}${if (step.isAnswered) "" else ", unanswered"})"
        is Step.IgnoreUsed -> "IgnoreUsed(${step.id})"
    }

    @Test fun everyAnswerForEveryHolding() {
        val table = listOf(
            // held, answer, spent → steps
            Triple<String?, AdsResponse, Set<String>>(null, answer(ad("x")), emptySet()) to listOf("Replace(x)"),
            Triple("x", answer(ad("x")), emptySet<String>()) to listOf("Keep"),
            Triple("x", answer(ad("y")), emptySet<String>()) to listOf("Drop", "Replace(y)"),
            Triple("x", answer(noFill(NoFillReason.FrequencyCap)), emptySet<String>()) to listOf("Drop", "NoFill(${NoFillReason.FrequencyCap})"),
            Triple("x", answer(ad("y")), setOf("y")) to listOf("Drop", "IgnoreUsed(y)"),
            Triple(null, answer(), emptySet<String>()) to listOf("NoFill(${NoFillReason.Unknown}, unanswered)"),
            Triple("x", answer(), emptySet<String>()) to listOf("Keep"),
        )
        for ((given, expected) in table) {
            val (held, response, spent) = given
            assertEquals(expected, steps(held, response, spent), "held=$held spent=$spent")
        }
    }

    @Test fun everyFormatGetsItsSteps() {
        val steps = AdStore.plan(answer(), emptyMap(), emptySet())
        assertEquals(AdFormat.entries.toList(), steps.map { it.format })
    }

    @Test fun aResultWithNeitherAdNorReasonIsAnUnknownNoFill() {
        val bare = answer(AdsResponse.FormatResult(pause, emptyList(), null))
        assertEquals(listOf("NoFill(${NoFillReason.Unknown})"), steps(null, bare))
        assertEquals(listOf("Drop", "NoFill(${NoFillReason.Unknown})"), steps("x", bare))
    }

    @Test fun aSpentIdIsIgnoredOnlyWhenItIsNotTheOneHeld() {
        assertEquals(listOf("Keep"), steps("x", answer(ad("x")), setOf("x")), "the held ad answered again: kept")
        assertEquals(listOf("IgnoreUsed(y)"), steps(null, answer(ad("y")), setOf("y")))
    }

    @Test fun onlyTheFirstAdOfAResultCounts() {
        val two = answer(AdsResponse.FormatResult(pause, listOf(payload("y"), payload("z")), null))
        assertEquals(listOf("Drop", "Replace(y)"), steps("x", two))
        assertEquals(listOf("Keep"), steps("y", two))
        assertEquals(listOf("Drop", "Replace(y)"), steps("z", two), "the held ad second in the list is replaced by the first")
    }

    @Test fun theKillSwitchAnswersNoFormat() {
        val disabled = AdsResponse.Disabled("killed")
        assertEquals(null, disabled.result(pause))
        assertEquals(listOf("Keep"), steps("x", disabled), "as an answer that leaves the format out")
        assertEquals(listOf("NoFill(${NoFillReason.Unknown}, unanswered)"), steps(null, disabled))
    }
}
