package so.kontext.video.internal.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.SdkTest
import so.kontext.video.internal.diagnostics.Logger
import java.util.concurrent.TimeUnit

/** A beacon: one GET, its redirects, one deadline for the whole chain, a share of the workers per host, a status read and never a body. */
@RunWith(RobolectricTestRunner::class)
class BeaconClientTest : SdkTest() {
    @Test fun aBeaconIsOneGetToItsUrl() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            BeaconClient.send(server.url("/beacon?e=impression").toString(), "ad: impression")
            val deadline = System.currentTimeMillis() + 5_000
            var request: okhttp3.mockwebserver.RecordedRequest? = null
            while (request == null && System.currentTimeMillis() < deadline) {
                idle(10)
                request = server.takeRequest(10, TimeUnit.MILLISECONDS)
            }
            assertEquals("GET /beacon?e=impression HTTP/1.1", request!!.requestLine)
        } finally {
            server.shutdown()
        }
    }

    /** The requests [server] received within five seconds of [count], polling as a beacon is sent off the main thread. */
    private fun received(server: MockWebServer, count: Int): List<okhttp3.mockwebserver.RecordedRequest> {
        val requests = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) {
            idle(10)
            server.takeRequest(10, TimeUnit.MILLISECONDS)?.let { requests += it }
        }
        return requests
    }

    @Test fun aBeaconFollowsRedirectsToAnotherServer() {
        // A tracking chain: the first server hands the beacon on to another (another host, here another port).
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/hop?e=impression").toString()))
            second.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/final?e=impression"))
            second.enqueue(MockResponse().setResponseCode(204))
            BeaconClient.send(first.url("/beacon?e=impression").toString(), "ad: impression")
            assertEquals(listOf("/beacon?e=impression"), received(first, 1).map { it.path })
            assertEquals("a relative Location too", listOf("/hop?e=impression", "/final?e=impression"), received(second, 2).map { it.path })
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    @Test fun aBeaconStopsAfterTenRedirects() {
        val server = MockWebServer().apply { start() }
        try {
            repeat(HttpTransport.MAX_REDIRECTS + 5) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again"))
            }
            BeaconClient.send(server.url("/beacon").toString(), "ad: impression")
            val requests = received(server, HttpTransport.MAX_REDIRECTS + 2)
            assertEquals("the first request and ten redirects, no more", HttpTransport.MAX_REDIRECTS + 1, requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test fun aBeaconHasOneDeadlineForItsWholeRedirectChain() {
        val server = MockWebServer().apply { start() }
        BeaconClient.timeoutMillis = 500
        try {
            // Each hop answers in 200 ms, under any idle timeout: only a deadline for the whole chain stops it.
            repeat(HttpTransport.MAX_REDIRECTS + 1) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again").setHeadersDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            }
            BeaconClient.send(server.url("/beacon").toString(), "ad: impression")
            val started = System.currentTimeMillis()
            while (BeaconClient.inFlight.get() == 0 && System.currentTimeMillis() - started < 2_000) Thread.sleep(5)
            while (BeaconClient.inFlight.get() > 0 && System.currentTimeMillis() - started < 5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                Thread.sleep(20)
            }
            assertEquals("the worker is free", 0, BeaconClient.inFlight.get())
            assertTrue("given up near the deadline, not after every hop", System.currentTimeMillis() - started < 1_800)
            assertTrue("not every hop was followed", server.requestCount < HttpTransport.MAX_REDIRECTS)
        } finally {
            BeaconClient.timeoutMillis = 10_000
            server.shutdown()
        }
    }

    /** A tracker whose answers wait for [release]; [arrived] counts the requests that reached it. */
    private class HeldTracker(private val status: Int = 204) {
        val release = java.util.concurrent.CountDownLatch(1)
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    arrived.incrementAndGet()
                    release.await(30, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(status)
                }
            }
            start()
        }

        fun shutdown() {
            release.countDown()
            server.shutdown()
        }
    }

    /**
     * Runs the main thread's work (no virtual time passes, so no beacon reaches its deadline) until [done], as the
     * beacon workers on their own threads go on. [guardMillis] of real time only ends a test that would hang.
     */
    private fun pumpUntil(guardMillis: Long = 10_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + guardMillis * 1_000_000
        while (!done()) {
            if (System.nanoTime() > end) return false
            idle()
            Thread.sleep(5)
        }
        return true
    }

    @Test fun aHealthyTrackerIsNotHeldBehindOneThatNeverAnswers() {
        // More beacons to one tracker that holds them all than there are beacon workers: it gets only its own
        // share of them, and another tracker's beacon of the same moment still goes out before any is answered.
        val hung = HeldTracker()
        val healthy = HeldTracker().also { it.release.countDown() }
        try {
            repeat(20) { BeaconClient.send(hung.server.url("/beacon?e=$it").toString(), "ad: impression") }
            assertTrue("the hung tracker took its turns", pumpUntil { hung.arrived.get() == 4 })
            BeaconClient.send(healthy.server.url("/beacon?e=impression").toString(), "other: impression")
            assertTrue("the other tracker's beacon went out while the hung ones wait", pumpUntil { healthy.arrived.get() == 1 })
            assertEquals("at most four to one host at once", 4, hung.arrived.get())
        } finally {
            hung.shutdown()
            healthy.shutdown()
        }
    }

    @Test fun aBeaconWaitingItsTurnGetsItsWholeDeadlineOnceSent() {
        // Four beacons hold the host's turns until their deadline; a fifth waits that long, then gets the whole
        // deadline from when it is sent. Time here is the main looper's: only idle(ms) moves it.
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        Logger.sink = { _, message -> logs += message }
        val tracker = HeldTracker(status = 404)
        BeaconClient.timeoutMillis = 1_000
        try {
            repeat(4) { BeaconClient.send(tracker.server.url("/beacon?e=$it").toString(), "held: impression") }
            BeaconClient.send(tracker.server.url("/beacon?e=queued").toString(), "queued: impression")
            assertTrue(pumpUntil { tracker.arrived.get() == 4 })
            idle(1_100) // the four reach their deadline; the fifth is sent
            assertTrue("the queued beacon is sent", pumpUntil { tracker.arrived.get() == 5 })
            assertEquals(4, logs.count { it.startsWith("held: impression beacon gave up") })
            idle(800) // 1.9 s after it was queued, 0.8 s or more after it was sent
            assertTrue("within its deadline: $logs", logs.none { it.startsWith("queued:") })
            tracker.release.countDown()
            assertTrue("its answer is read: $logs", pumpUntil { logs.any { it == "queued: impression beacon answered 404" } })
        } finally {
            BeaconClient.timeoutMillis = 10_000
            tracker.shutdown()
        }
    }

    @Test fun aBeaconReadsTheStatusAndNeverTheBody() {
        // A 6 MiB answer to one beacon: the SDK reads the status line and closes, so the server cannot write it all.
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val bodySize = 6 * 1024 * 1024
        var written = 0
        var requestLine: String? = null
        val serving = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                requestLine = reader.readLine()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val out = socket.getOutputStream()
                try {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $bodySize\r\n\r\n".toByteArray())
                    out.flush()
                    val chunk = ByteArray(64 * 1024)
                    while (written < bodySize) {
                        out.write(chunk)
                        written += chunk.size
                    }
                    out.flush()
                } catch (_: java.io.IOException) {
                    // The SDK hung up: what it never read could not be sent.
                }
            }
        }
        serving.start()
        try {
            BeaconClient.send("http://127.0.0.1:${server.localPort}/beacon?e=impression", "ad: impression")
            while (serving.isAlive) {
                idle(10)
                serving.join(10)
            }
            assertEquals("GET /beacon?e=impression HTTP/1.1", requestLine)
            assertTrue("$written of $bodySize bytes went out", written < bodySize)
        } finally {
            server.close()
        }
    }
}
