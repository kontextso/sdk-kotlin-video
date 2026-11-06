package so.kontext.video.internal.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.internal.payloads.JsonParser
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executor

/** What the worker never lets through: an exception of any kind, a body of any size, parsing on the caller's thread. */
class ApiClientLimitsTest {
    private val server = MockWebServer()
    private val body = buildJsonObject { put("token", JsonPrimitive("t-1")) }
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())

    /** Marks the worker's frames, so a stack trace shows what ran there. */
    private class OnWorker(private val work: Runnable) : Runnable {
        override fun run() = work.run()
    }

    /** A worker thread of its own whose uncaught exceptions the test sees, as the app's process would. */
    private val worker = Executor { work ->
        Thread(OnWorker(work)).apply { setUncaughtExceptionHandler { _, error -> uncaught += error } }.start()
    }

    @BeforeEach fun start() = server.start()

    @AfterEach fun stop() = server.shutdown()

    private fun post() = runBlocking { ApiClient(server.url("/").toString(), ApiClient.DEFAULT_TIMEOUT_MILLIS, worker).post("config", body) }

    @Test fun anyExceptionInTheExchangeIsATransportErrorAtOnce() {
        // The redirect target's port is out of range: opening it throws IllegalArgumentException, not an IOException.
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:99999/x"))
        val error = assertThrows<ApiException.Transport> { post() }
        assertFalse(error.cause is SocketTimeoutException, "the caller hears the failure, not the timeout")
        assertTrue(error.cause?.cause is IllegalArgumentException, "${error.cause}")
        assertEquals(emptyList<Throwable>(), uncaught, "nothing left on the worker")
    }

    @Test fun aBodyOverFiveMegabytesIsATransportErrorAnswerOrError() {
        val tooBig = "{\"a\":\"" + "x".repeat(ApiClient.MAX_BODY_BYTES) + "\"}"
        server.enqueue(MockResponse().setBody(tooBig))
        assertThrows<ApiException.Transport> { post() }
        server.enqueue(MockResponse().setResponseCode(500).setBody(tooBig))
        assertThrows<ApiException.Transport> { post() }
        // No length announced: the read stops past the limit.
        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8(tooBig), 64 * 1024))
        assertThrows<ApiException.Transport> { post() }
    }

    @Test fun aBodyOfExactlyFiveMegabytesIsRead() {
        val prefix = "{\"a\":\""
        val atLimit = prefix + "x".repeat(ApiClient.MAX_BODY_BYTES - prefix.length - 2) + "\"}"
        assertEquals(ApiClient.MAX_BODY_BYTES, atLimit.length)
        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8(atLimit), 64 * 1024))
        assertTrue(post()!!.jsonObject.containsKey("a"))
    }

    @Test fun anAnswerNestedDeeperThanTheIosDecoderReadsIsMalformed() {
        fun nested(levels: Int) = "{\"a\":" + "[".repeat(levels - 1) + "]".repeat(levels - 1) + "}"
        server.enqueue(MockResponse().setBody(nested(JsonParser.MAX_DEPTH)))
        assertTrue(post()!!.jsonObject.containsKey("a"), "512 levels, the iOS decoder's limit, are read")
        server.enqueue(MockResponse().setBody(nested(JsonParser.MAX_DEPTH + 1)))
        assertThrows<ApiException.Decoding> { post() }
        // Brackets inside strings, an escaped quote among them, are text, not nesting.
        val text = "\"\\\"" + "[".repeat(JsonParser.MAX_DEPTH * 2) + "\""
        server.enqueue(MockResponse().setBody("{\"a\": $text, \"b\": [1]}"))
        assertTrue(post()!!.jsonObject.containsKey("b"))
    }

    @Test fun theDepthCheckReadsEscapesAsTheParserDoes() {
        val bs = "\\"
        val q = "\""
        val deep = "[".repeat(JsonParser.MAX_DEPTH * 2)
        // Text: an escaped quote, an escaped backslash then an escaped quote, a bracket written as a code. The
        // string goes on, and its brackets are not nesting.
        for (text in listOf(q + "sfs" + bs + q + deep + q, q + "a" + bs + bs + bs + q + deep + q, q + bs + "u005B" + deep + q)) {
            server.enqueue(MockResponse().setBody("{\"a\": $text, \"b\": [1]}"))
            assertTrue(post()!!.jsonObject.containsKey("b"), text.take(12))
        }
        // An escaped backslash then a quote: the string ends (`C:\`), and the brackets after it are nesting.
        val nesting = "[".repeat(JsonParser.MAX_DEPTH) + "]".repeat(JsonParser.MAX_DEPTH)
        server.enqueue(MockResponse().setBody("{\"p\": ${q}C:$bs$bs$q, \"x\": $nesting}"))
        assertThrows<ApiException.Decoding> { post() }
    }

    @Test fun theAnswerIsParsedOnTheWorkerNotOnTheCallersThread() {
        server.enqueue(MockResponse().setBody("{not json"))
        val error = assertThrows<ApiException.Decoding> { post() }
        val frames = error.cause!!.stackTrace.map { it.className }
        assertTrue(OnWorker::class.java.name in frames, "parsed where it was read")
    }
}
