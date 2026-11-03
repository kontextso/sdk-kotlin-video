package so.kontext.video.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.User
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.device.Consent
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.playback.ContentProgress
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Labels
import so.kontext.video.internal.support.Logger

/** The pure rules: labels, the overlay's video-time count, consent sections, the models and the request body. */
class LogicTest {
    private var now = 0L

    @AfterEach fun restore() = Environment.reset()

    @Test fun labelsKeepOnlyWhatTheServerAccepts() {
        val kept = Labels.checked(
            mapOf("stage" to "first_episode", "Bad-Key" to "x", "empty" to "", "long" to "v".repeat(65), "layout" to "fullscreen"),
            "ep",
        )
        assertEquals(mapOf("layout" to "fullscreen", "stage" to "first_episode"), kept)
        val many = Labels.checked((1..25).associate { "k%02d".format(it) to "v" }, "ep")
        assertEquals(20, many.size)
        assertTrue("k01" in many && "k21" !in many, "the first in key order are kept")
    }

    @Test fun anOverlayCountsVideoTimeAndNotSeeks() {
        Environment.now = { now }
        val progress = ContentProgress(durationMillis = 3_000, playheadMillis = 0)
        now += 1_000
        assertFalse(progress.advance(1_000, counting = true))
        now += 250
        assertFalse(progress.advance(60_000, counting = true), "a jump of a minute in a quarter second is a seek")
        assertEquals(1_000, progress.elapsedMillis)
        now += 1_000
        assertFalse(progress.advance(61_000, counting = false), "not while hidden")
        now += 1_000
        assertFalse(progress.advance(62_000, counting = true))
        now += 1_000
        assertTrue(progress.advance(63_000, counting = true))
        assertEquals(3_000, progress.elapsedMillis)
    }

    @Test fun fastPlaybackCountsAndABackwardStepDoesNot() {
        Environment.now = { now }
        val progress = ContentProgress(durationMillis = 100_000, playheadMillis = 10_000)
        now += 1_000
        progress.advance(14_000, counting = true) // 4×
        now += 1_000
        progress.advance(2_000, counting = true) // a loop back
        assertEquals(4_000, progress.elapsedMillis)
    }

    @Test fun gppSectionsAreParsedStrictly() {
        assertEquals(listOf(2, 7), Consent.parseSections("2_7"))
        assertNull(Consent.parseSections("2_x"))
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
        val json = AdsRequest.content(content).toString()
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
            AdsRequest.content(full).toString(),
        )
    }

    @Test fun emptyUserFieldsAreLeftOutAndNeverLogged() {
        val user = User {
            id = ""
            email = "a@b.test"
        }
        assertEquals("""{"email":"a@b.test"}""", AdsRequest.user(user).toString())
        assertFalse("a@b.test" in user.toString())
    }

    @Test fun theWireNamesMapBothWays() {
        assertEquals(NoFillReason.GeoRestricted, NoFillReason.from("geoRestricted"))
        assertEquals("geoRestricted", NoFillReason.GeoRestricted.toString())
        assertEquals(VideoAd.Click.Area.EndCard, VideoAd.Click.Area.from("endCard"))
        assertEquals(VideoAd.Click.Area.Creative, VideoAd.Click.Area.from(null))
        assertEquals(VideoAd.Click.Area.Other("cta"), VideoAd.Click.Area.from("cta"))
    }

    @Test fun reportFieldsAreCutAtACodePoint() {
        assertEquals("a".repeat(64), ErrorReporter.capped("a".repeat(80)))
        val emoji = "😀".repeat(20) // 4 bytes each
        assertEquals("😀".repeat(16), ErrorReporter.capped(emoji))
    }

    @Test fun aUrlIsLoggedAsSchemeAndHostOnly() {
        assertEquals("https://brand.test", Logger.describe("https://user:pw@brand.test/path?q=secret"))
        assertEquals("mailto:", Logger.describe("mailto:someone@x.test"))
    }
}
