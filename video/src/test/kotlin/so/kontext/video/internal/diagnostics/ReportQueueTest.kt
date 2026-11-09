package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The error reports' queue alone: its bounds, what it drops, and the batches it makes. */
class ReportQueueTest {
    private fun report(n: Int, pad: Int = 0) = buildJsonObject {
        put("n", n)
        if (pad > 0) put("pad", "x".repeat(pad))
    }

    private fun envelope(reports: List<JsonObject>) = JsonObject(mapOf("errors" to JsonArray(reports)))

    private fun numbers(batch: ReportQueue.Batch) = batch.body["errors"]!!.jsonArray.map { (it as JsonObject)["n"]!!.jsonPrimitive.content.toInt() }

    @Test fun anEmptyQueueMakesNoBatch() {
        val queue = ReportQueue()
        assertTrue(queue.isEmpty())
        assertNull(queue.makeBatch(::envelope))
    }

    @Test fun pastMaxBufferedTheOldestGo() {
        val queue = ReportQueue()
        repeat(ReportQueue.MAX_BUFFERED + 3) { queue.add(report(it)) }
        assertEquals(ReportQueue.MAX_BUFFERED, queue.size)
        val batch = queue.makeBatch(::envelope)!!
        assertEquals(ReportQueue.MAX_BATCH, batch.count)
        assertEquals((3 until 3 + ReportQueue.MAX_BATCH).toList(), numbers(batch), "the oldest kept first")
    }

    @Test fun pastMaxBufferedBytesTheOldestGo() {
        val queue = ReportQueue()
        val pad = 10_000
        repeat(40) { queue.add(report(it, pad)) }
        assertTrue(queue.sizes.sum() <= ReportQueue.MAX_BUFFERED_BYTES)
        assertTrue(queue.size < 40)
        assertEquals(40 - queue.size, numbers(queue.makeBatch(::envelope)!!).first(), "the oldest went")
    }

    @Test fun aReportOverTheBatchLimitAloneIsNeverQueued() {
        val queue = ReportQueue()
        assertFalse(queue.add(report(1, ReportQueue.MAX_BATCH_BYTES)))
        assertTrue(queue.isEmpty())
    }

    @Test fun aBatchStaysWithinItsByteLimitAndTheRestWait() {
        val queue = ReportQueue()
        val pad = 20_000
        repeat(5) { queue.add(report(it, pad)) }
        val first = queue.makeBatch(::envelope)!!
        assertTrue(first.body.toString().toByteArray().size <= ReportQueue.MAX_BATCH_BYTES)
        assertEquals(listOf(0, 1, 2), numbers(first))
        assertEquals(listOf(3, 4), numbers(queue.makeBatch(::envelope)!!))
        assertNull(queue.makeBatch(::envelope))
    }

    @Test fun clearDropsEverything() {
        val queue = ReportQueue()
        repeat(ReportQueue.MAX_BUFFERED + 1) { queue.add(report(it)) }
        queue.clear()
        assertTrue(queue.isEmpty())
        assertNull(queue.makeBatch(::envelope))
    }

    @Test fun aReportThatFitsAloneButNotInItsEnvelopeIsDropped() {
        val queue = ReportQueue()
        // Within the batch limit alone, over it once the envelope wraps it.
        val big = report(1, ReportQueue.MAX_BATCH_BYTES - 20)
        assertTrue(big.toString().toByteArray().size <= ReportQueue.MAX_BATCH_BYTES)
        assertTrue(queue.add(big))
        queue.add(report(2))
        val batch = queue.makeBatch(::envelope)!!
        assertEquals(listOf(2), numbers(batch))
        assertTrue(queue.isEmpty())
    }

    @Test fun aQueueOfOnlyReportsTooBigForTheEnvelopeMakesNoBatch() {
        val queue = ReportQueue()
        queue.add(report(1, ReportQueue.MAX_BATCH_BYTES - 20))
        assertNull(queue.makeBatch(::envelope))
        assertTrue(queue.isEmpty(), "the report went")
        queue.add(report(2))
        assertEquals(listOf(2), numbers(queue.makeBatch(::envelope)!!))
    }

    @Test fun aReportAtTheBatchLimitIsQueuedAndOneByteOverIsNot() {
        fun sized(bytes: Int): JsonObject {
            val empty = report(1, 1).toString().toByteArray().size - 1
            return report(1, bytes - empty)
        }
        assertEquals(ReportQueue.MAX_BATCH_BYTES, sized(ReportQueue.MAX_BATCH_BYTES).toString().toByteArray().size)
        assertTrue(ReportQueue().add(sized(ReportQueue.MAX_BATCH_BYTES)))
        assertFalse(ReportQueue().add(sized(ReportQueue.MAX_BATCH_BYTES + 1)))
    }

    @Test fun aBatchHoldsAtMostMaxBatchReportsAndTheRestFollowInOrder() {
        val queue = ReportQueue()
        repeat(ReportQueue.MAX_BATCH * 2 + 1) { queue.add(report(it)) }
        val sizes = generateSequence { queue.makeBatch(::envelope) }.map { numbers(it) }.toList()
        assertEquals(listOf(ReportQueue.MAX_BATCH, ReportQueue.MAX_BATCH, 1), sizes.map { it.size })
        assertEquals((0..ReportQueue.MAX_BATCH * 2).toList(), sizes.flatten())
    }
}
