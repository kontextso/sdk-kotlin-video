package so.kontext.video.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.internal.diagnostics.ReportQueue

/** The error reports' queue alone: its bounds, what it drops and counts, and the batches it makes. */
class ReportQueueTest {
    private fun report(n: Int, pad: Int = 0) = buildJsonObject {
        put("n", n)
        if (pad > 0) put("pad", "x".repeat(pad))
    }

    private fun envelope(reports: List<JsonObject>, dropped: Int) = JsonObject(mapOf("dropped" to JsonPrimitive(dropped), "errors" to JsonArray(reports)))

    private fun numbers(batch: ReportQueue.Batch) = batch.body["errors"]!!.jsonArray.map { (it as JsonObject)["n"]!!.jsonPrimitive.content.toInt() }

    private fun dropped(batch: ReportQueue.Batch) = batch.body["dropped"]!!.jsonPrimitive.content.toInt()

    @Test fun anEmptyQueueMakesNoBatch() {
        val queue = ReportQueue()
        assertTrue(queue.isEmpty())
        assertNull(queue.makeBatch(::envelope))
    }

    @Test fun pastMaxBufferedTheOldestGoAndTheNextBatchCountsThem() {
        val queue = ReportQueue()
        repeat(ReportQueue.MAX_BUFFERED + 3) { queue.add(report(it)) }
        assertEquals(ReportQueue.MAX_BUFFERED, queue.size)
        assertEquals(3, queue.dropped)
        val batch = queue.makeBatch(::envelope)!!
        assertEquals(ReportQueue.MAX_BATCH, batch.count)
        assertEquals((3 until 3 + ReportQueue.MAX_BATCH).toList(), numbers(batch), "the oldest kept first")
        assertEquals(3, dropped(batch))
        assertEquals(0, queue.dropped, "said once")
        assertEquals(0, dropped(queue.makeBatch(::envelope)!!))
    }

    @Test fun pastMaxBufferedBytesTheOldestGo() {
        val queue = ReportQueue()
        val pad = 10_000
        repeat(40) { queue.add(report(it, pad)) }
        assertTrue(queue.sizes.sum() <= ReportQueue.MAX_BUFFERED_BYTES)
        assertEquals(40 - queue.size, queue.dropped)
        assertTrue(queue.dropped > 0)
    }

    @Test fun aReportOverTheBatchLimitAloneIsDroppedAndCounted() {
        val queue = ReportQueue()
        assertFalse(queue.add(report(1, ReportQueue.MAX_BATCH_BYTES)))
        assertTrue(queue.isEmpty())
        assertEquals(1, queue.dropped)
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

    @Test fun clearDropsEverythingAndTheCount() {
        val queue = ReportQueue()
        repeat(ReportQueue.MAX_BUFFERED + 1) { queue.add(report(it)) }
        queue.clear()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.dropped)
    }
}
