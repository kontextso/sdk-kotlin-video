package so.kontext.video.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.User
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.content.ContentTimeClock
import so.kontext.video.internal.diagnostics.ReportFormat
import so.kontext.video.internal.diagnostics.problems
import so.kontext.video.internal.payloads.leftOut
import so.kontext.video.internal.payloads.missingRequired
import so.kontext.video.internal.payloads.noFillReasonFrom
import so.kontext.video.internal.payloads.toJson
import so.kontext.video.internal.platform.Environment

/** The pure rules: the overlay's video-time count, the models and the request body. */
class LogicTest {
    private var now = 0L

    @AfterEach fun restore() = Environment.reset()

    @Test fun anOverlayCountsVideoTimeAndNotSeeks() {
        Environment.now = { now }
        val progress = ContentTimeClock(durationMillis = 3_000, playheadMillis = 0)
        now += 1_000
        assertFalse(progress.advance(1_000, isCounting = true))
        now += 250
        assertFalse(progress.advance(60_000, isCounting = true), "a jump of a minute in a quarter second is a seek")
        assertEquals(1_000, progress.elapsedMillis)
        now += 1_000
        assertFalse(progress.advance(61_000, isCounting = false), "not while hidden")
        now += 1_000
        assertFalse(progress.advance(62_000, isCounting = true))
        now += 1_000
        assertTrue(progress.advance(63_000, isCounting = true))
        assertEquals(3_000, progress.elapsedMillis)
    }

    @Test fun fastPlaybackCountsAndABackwardStepDoesNot() {
        Environment.now = { now }
        val progress = ContentTimeClock(durationMillis = 100_000, playheadMillis = 10_000)
        now += 1_000
        progress.advance(14_000, isCounting = true) // 4×
        now += 1_000
        progress.advance(2_000, isCounting = true) // a loop back
        assertEquals(4_000, progress.elapsedMillis)
    }

    @Test fun aNonFiniteSpeedReadsAsUnknown() {
        Environment.now = { now }
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val playing = ContentTimeClock(durationMillis = 10_000, playheadMillis = 0)
            now += 1_000
            playing.advance(1_000, isCounting = true, speed = speed)
            assertEquals(1_000, playing.elapsedMillis, "ordinary playback counts at speed $speed")
            val seeking = ContentTimeClock(durationMillis = 100_000, playheadMillis = 0)
            now += 250
            seeking.advance(60_000, isCounting = true, speed = speed)
            assertEquals(0, seeking.elapsedMillis, "a minute in a quarter second is a seek at speed $speed")
        }
    }

    @Test fun theServerUrlIsAnHttpBaseWithAHostAndNothingElse() {
        val good = listOf("https://a.b", "HTTPS://a.b", "http://10.0.2.2:3002/", "https://a.b/prefix/", "http://localhost:5175")
        for (url in good) assertTrue(Configuration("t") { adServerUrl = url }.problems.isEmpty(), url)
        val bad = listOf("https://", "https://u:p@a.b", "https://a.b:0", "https://a.b:65536", "https://a.b?x=1", "https://a.b#f", "ftp://a.b", "a.b", "", " https://a.b")
        for (url in bad) assertEquals(1, Configuration("t") { adServerUrl = url }.problems.size, url)
    }

    @Test fun theConfigurationSaysWhatIsWrong() {
        assertTrue(Configuration("token").problems.isEmpty())
        assertEquals(listOf("publisherToken is empty"), Configuration("").problems)
        assertTrue(Configuration("t") { adServerUrl = "server.test" }.problems.single().startsWith("adServerUrl"))
    }

    @Test fun theContentLeavesOutWhatTheServerWouldRefuse() {
        val content = Content("ep", "en") {
            durationMillis = 0
            episodeNumber = 0
            series = Content.Series("") { episodeCount = 3 }
        }
        val json = content.toJson().toString()
        assertEquals("""{"id":"ep","language":"en"}""", json)
        assertEquals(3, content.leftOut.size)
        assertEquals(listOf("id is empty", "language is empty"), Content("", "").missingRequired)
        val full = Content("ep", "en") {
            mediaRating = Content.Rating.SAFE
            durationMillis = 92_000
            series = Content.Series("s") { episodeCount = 40 }
        }
        assertEquals(
            """{"id":"ep","language":"en","mediaRating":"safe","duration":92000,"series":{"id":"s","episodeCount":40}}""",
            full.toJson().toString(),
        )
    }

    @Test fun emptyUserFieldsAreLeftOutAndNeverLogged() {
        val user = User {
            id = ""
            email = "a@b.test"
        }
        assertEquals("""{"email":"a@b.test"}""", user.toJson().toString())
        assertFalse("a@b.test" in user.toString())
    }

    @Test fun theWireNamesMapBothWays() {
        assertEquals(NoFillReason.GeoRestricted, noFillReasonFrom("geoRestricted"))
        assertEquals("geoRestricted", NoFillReason.GeoRestricted.toString())
        assertEquals(VideoAd.Click.Area.EndCard, VideoAd.Click.Area.from("endCard"))
        assertEquals(VideoAd.Click.Area.Creative, VideoAd.Click.Area.from(null))
        assertEquals(VideoAd.Click.Area.Other("cta"), VideoAd.Click.Area.from("cta"))
    }

    @Test fun reportFieldsAreCutAtACodePoint() {
        assertEquals("a".repeat(64), ReportFormat.capped("a".repeat(80)))
        val emoji = "😀".repeat(20) // 4 bytes each
        assertEquals("😀".repeat(16), ReportFormat.capped(emoji))
    }
}
