package so.kontext.video

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.FrameProtocol
import so.kontext.video.internal.measurement.ActiveTimeClock
import so.kontext.video.internal.network.BeaconClient
import so.kontext.video.internal.network.HttpTransport
import so.kontext.video.internal.screen.InlineAdView
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger
import java.util.concurrent.TimeUnit

/** The pieces around an ad: the host view, the clock, the bridge's remaining paths, beacons, logging and the facade. */
@RunWith(RobolectricTestRunner::class)
class PartsTest : SdkTest() {
    private var callbackError: VideoAdException? = null

    @Test fun theHostRefitsTheAdWhenTheContainerResizesAndEndsItWhenTooSmall() {
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        // Inside Robolectric's window, whose system insets the host keeps the ad clear of.
        val container = container(width = 320, height = 400)
        ad.show(container)
        val host = container.getChildAt(0) as InlineAdView
        // What the host lays the ad out at, for the container's size and insets (Robolectric's WebView keeps no bounds).
        val place = host.place!!
        assertEquals(android.graphics.Rect(0, 320, 320, 400), place(320, 400, android.graphics.Rect()))
        assertEquals("follows a shorter container", android.graphics.Rect(0, 220, 320, 300), place(320, 300, android.graphics.Rect()))
        assertEquals("kept clear of the insets", android.graphics.Rect(0, 200, 320, 280), place(320, 300, android.graphics.Rect(0, 0, 0, 20)))
        assertNull("no room", place(100, 300, android.graphics.Rect()))
        host.forceLayout()
        host.layout(0, 0, 100, 300) // too narrow for any variant at half size
        idle()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun theClockCountsOnlyWhileTheWindowShowsAndTellsWhenItComesBack() {
        var now = 0L
        Environment.now = { now }
        var elapsed = false
        var activations = 0
        val clock = ActiveTimeClock(durationMillis = 3_000, adId = "ad-1") { elapsed = true }
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

    @Test fun theBridgeIgnoresWhatDoesNotFitItsOrderAndReportsABrokenEnvelopeOnce() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val sent = mutableListOf<String>()
        bridge.onSend = { type, _ -> sent += type }
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = FrameBridge.Origin.of("https://frames.test")
        bridge.show() // before loaded: ignored
        assertTrue(sent.isEmpty())
        bridge.receive("not json", page, true)
        bridge.receive("""{"type":"ad.loaded"}""", page, true)
        assertEquals("one report for a broken page", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        bridge.show(so.kontext.video.internal.payloads.AdPayload.Size(320, 80))
        bridge.exposure(0.5, 0.0, 0.0, 10.0, 10.0)
        bridge.receive("""{"adId":"ad-1","type":"something.new"}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.progress","offset":"soon"}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.mute","playhead":1234}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.start","playhead":0}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.complete","playhead":30000}""", page, true)
        assertEquals(listOf("ad.show", "ad.exposure"), sent)
        assertEquals(4, events.size)
        assertEquals(FrameEvent.VideoEvent.MUTE, (events[1] as FrameEvent.Video).event)
        assertEquals(1234, (events[1] as FrameEvent.Video).playheadMillis)
        bridge.hide()
        assertEquals("ad.hide", sent.last())
        assertEquals(FrameProtocol.State.ENDED, bridge.state)
        bridge.receive("""{"adId":"ad-1","type":"video.start"}""", page, true) // ended: nothing
        assertEquals(4, events.size)
    }

    @Test fun theVideoCountsOnlyAfterVideoStartAndADroppedMessageIsReportedOnce() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = FrameBridge.Origin.of("https://frames.test")
        fun send(type: String, fields: String = "") = bridge.receive("""{"adId":"ad-1","type":"$type"$fields}""", page, true)
        send("ad.loaded")
        bridge.show()
        events.clear()
        for (early in listOf("video.firstQuartile", "video.midpoint", "video.thirdQuartile", "video.pause", "video.resume", "video.complete")) send(early)
        send("video.progress", ""","offset":1000""")
        assertTrue("nothing of the video before its start: $events", events.isEmpty())
        assertEquals("one report, its detail named", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        assertEquals(listOf("video.beforeStart"), ErrorReporter.bufferedDetails)
        send("video.mute")
        send("video.unmute")
        assertEquals("the viewer's mute and unmute go before the start", listOf(FrameEvent.VideoEvent.MUTE, FrameEvent.VideoEvent.UNMUTE), events.map { (it as FrameEvent.Video).event })
        send("video.start")
        send("video.firstQuartile")
        send("video.progress", ""","offset":1000""")
        send("video.complete")
        assertEquals(listOf("Video", "Video", "Video", "Video", "Progress", "Complete"), events.map { it::class.simpleName })
        val skipping = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val skipped = mutableListOf<FrameEvent>()
        skipping.onEvent = { skipped += it }
        skipping.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        skipping.show()
        skipping.receive("""{"adId":"ad-1","type":"video.skip","playhead":0}""", page, true)
        assertTrue("a skip goes before the start", skipped.last() is FrameEvent.Skip)
    }

    @Test fun aLinearAdsPlayheadAndProgressStopAtItsDurationAndASecond() {
        val page = FrameBridge.Origin.of("https://frames.test")
        fun shownBridge(duration: Long?): Pair<FrameBridge, MutableList<FrameEvent>> {
            val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", true, duration)
            val events = mutableListOf<FrameEvent>()
            bridge.onEvent = { events += it }
            bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
            bridge.show()
            bridge.receive("""{"adId":"ad-1","type":"video.start","playhead":0}""", page, true)
            events.clear()
            return bridge to events
        }
        val (linear, events) = shownBridge(30_000)
        linear.receive("""{"adId":"ad-1","type":"video.progress","offset":50000,"playhead":1e12}""", page, true)
        val progress = events.single() as FrameEvent.Progress
        assertEquals("the offset stops at the end and a second", 31_000L, progress.offsetMillis)
        assertEquals("so does the playhead", 31_000L, progress.playheadMillis)
        linear.receive("""{"adId":"ad-1","type":"video.complete","playhead":30500}""", page, true)
        assertEquals("within the grace: as sent", 30_500L, (events.last() as FrameEvent.Complete).playheadMillis)
        val (overlay, overlayEvents) = shownBridge(null)
        overlay.receive("""{"adId":"ad-1","type":"video.complete","playhead":1e12}""", page, true)
        assertEquals("without a duration: a day", 86_400_000L, (overlayEvents.last() as FrameEvent.Complete).playheadMillis)
    }

    @Test fun aPageErrorAfterShowDefaultsTo405AndBeforeToTheLoadCode() {
        val page = FrameBridge.Origin.of("https://frames.test")
        val video = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/a", isVideo = true)
        var code = 0
        video.onEvent = { if (it is FrameEvent.Error) code = it.code }
        video.receive("""{"adId":"ad-1","type":"ad.error"}""", page, true)
        assertEquals("a video's load code", 400, code)
        val shown = FrameBridge(Environment.context!!, "ad-2", "https://frames.test/b", isVideo = false)
        shown.onEvent = { if (it is FrameEvent.Error) code = it.code }
        shown.receive("""{"adId":"ad-2","type":"ad.loaded"}""", page, true)
        shown.show()
        shown.receive("""{"adId":"ad-2","type":"ad.error"}""", page, true)
        assertEquals(405, code)
    }

    @Test fun onlyLinksToSomewhereMayOpen() {
        assertTrue(FrameBridge.isAllowed("https://brand.test"))
        assertTrue(FrameBridge.isAllowed("market://details?id=x"))
        assertFalse(FrameBridge.isAllowed("intent://x#Intent;end"))
        assertFalse(FrameBridge.isAllowed("javascript:alert(1)"))
        assertFalse(FrameBridge.isAllowed("no scheme"))
        assertFalse("a space", FrameBridge.isAllowed("https://brand.test/a b"))
        assertFalse("a web link without a host", FrameBridge.isAllowed("https://"))
        assertEquals(FrameBridge.Origin("https", "frames.test", 443), FrameBridge.Origin.of("HTTPS://Frames.Test/path"))
        assertEquals("https://frames.test:8443", FrameBridge.Origin.of("https://frames.test:8443/x")!!.rule)
        assertNull(FrameBridge.Origin.of("file:///x"))
    }

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

    @Test fun theLogShowsOnlyWhatItsLevelLets() {
        val lines = mutableListOf<String>()
        Logger.sink = { _, message -> lines += message }
        try {
            Logger.level = Configuration.LogLevel.WARNING
            Logger.debug { "debug" }
            Logger.info { "info" }
            Logger.warning { "warning" }
            Logger.error { "error" }
            Logger.level = Configuration.LogLevel.NONE
            Logger.error { "silent" }
            assertEquals(listOf("warning", "error"), lines)
        } finally {
            Logger.sink = null
            Logger.level = Configuration.LogLevel.WARNING
        }
    }

    @Test fun theFacadeHoldsWhatTheAppSets() {
        val user = User { id = "u-1" }
        KontextVideo.user = user
        KontextVideo.regulatory = Regulatory { coppa = true }
        KontextVideo.variantId = "b"
        assertSame(user, KontextVideo.user)
        assertEquals(true, KontextVideo.regulatory?.coppa)
        assertEquals("b", KontextVideo.variantId)
        assertEquals(KontextVideo.State.Enabled, KontextVideo.state)
        var asserted: String? = null
        Environment.assertionFailure = { asserted = it }
        KontextVideo.user = User { id = "" }
        assertNotNull("an empty id is a programmer error", asserted)
        assertTrue(KontextVideo.VERSION.isNotEmpty())
    }

    @Test fun theVersionIsReadAtRunTimeAndNoBuildConfigIsPublic() {
        // A field, not a compile-time constant an app would inline: the version the app ships with is the one read.
        val field = KontextVideo::class.java.getField("VERSION")
        assertTrue(java.lang.reflect.Modifier.isStatic(field.modifiers))
        assertEquals(so.kontext.video.internal.SDK_VERSION, field.get(null))
        assertTrue(
            "no generated BuildConfig in the SDK's public package",
            runCatching { Class.forName("so.kontext.video.BuildConfig") }.isFailure,
        )
    }

    @Test fun aSecondInitializeIsIgnored() {
        val configuration = Sdk.configuration
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("other"))
        assertSame(configuration, Sdk.configuration)
    }

    @Test fun formatWithACallbackDeliversTheError() {
        Sdk.disable("paused")
        var error: VideoAdException? = null
        owner().format(
            AdFormat.PAUSE,
            object : VideoAdsCallback<VideoAd> {
                override fun onSuccess(result: VideoAd) = org.junit.Assert.fail("disabled")

                override fun onError(error: VideoAdException) {
                    this@PartsTest.callbackError = error
                }
            },
        )
        idle()
        error = callbackError
        assertTrue(error is VideoAdException.Disabled)
    }

    @Test fun aVideoAdsWithoutAnIdAsksAndAfterDestroyRefusesEverything() {
        var asserted: String? = null
        Environment.assertionFailure = { asserted = it }
        val ads = owner(Content("", "en"))
        assertNotNull(asserted)
        assertTrue("ep" !in ads.toString())
        ads.destroy()
        assertTrue(ads.isDestroyed)
        ads.track(object : VideoAdsPlayer {
            override val currentPositionMillis = 0L
            override val isPlaying = true
        })
        assertFalse(ads.playback.isTracking)
    }

    @Test fun memoryPressureReleasesExpiredAdsOnly() {
        var now = 0L
        Environment.now = { now }
        val owner = owner()
        val ad = overlay(owner)
        owner.ads[AdFormat.OVERLAY] = ad
        Environment.context!!.applicationContext.let { (it as android.app.Application).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        assertNull("valid: kept", ad.invalidationReason)
        now = 10_000_000
        (Environment.context as android.app.Application).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
    }
}
