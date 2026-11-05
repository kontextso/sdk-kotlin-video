package so.kontext.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.payloads.emptyFields

/** The public models and value types: builders from Kotlin and Java, equality, and what their strings show. */
class ModelsTest {
    @Test fun contentFromTheJavaBuilderEqualsTheKotlinOne() {
        val java = Content.Builder("ep", "en")
            .mediaRating(Content.Rating.EXPLICIT).url("https://show.test/ep").title("Episode").categories(listOf("IAB1"))
            .description("d").durationMillis(92_000L).episodeNumber(6).keywords(listOf("k")).genre("romance")
            .series(Content.Series.Builder("s").title("Show").description("sd").episodeCount(40).build())
            .build()
        val kotlin = Content("ep", "en") {
            mediaRating = Content.Rating.EXPLICIT
            url = "https://show.test/ep"
            title = "Episode"
            categories = listOf("IAB1")
            description = "d"
            durationMillis = 92_000
            episodeNumber = 6
            keywords = listOf("k")
            genre = "romance"
            series = Content.Series("s") {
                title = "Show"
                description = "sd"
                episodeCount = 40
            }
        }
        assertEquals(java, kotlin)
        assertEquals(java.hashCode(), kotlin.hashCode())
        assertNotEquals(java, Content("ep", "en"))
        assertTrue("Episode" in java.toString() && "series=Series(id=s" in java.toString())
    }

    @Test fun aContentsListsCannotBeChangedFromOutside() {
        val keywords = mutableListOf("a")
        val content = Content("ep", "en") { this.keywords = keywords }
        keywords += "b"
        assertEquals(listOf("a"), content.keywords)
    }

    @Test fun userAndRegulatoryBuildBothWaysAndHidePersonalData() {
        val user = User.Builder().id("u-1").email("a@b.test").emailHash("h").keywords(listOf("k")).build()
        assertEquals(
            user,
            User {
                id = "u-1"
                email = "a@b.test"
                emailHash = "h"
                keywords = listOf("k")
            },
        )
        assertFalse("u-1" in user.toString() || "a@b.test" in user.toString())
        assertTrue(user.emptyFields.isEmpty())
        assertEquals(
            listOf("id", "emailHash"),
            User {
                id = ""
                emailHash = ""
            }.emptyFields,
        )
        val regulatory = Regulatory.Builder().gdpr(true).gdprConsent("tc").coppa(false).gpp("g").gppSid(listOf(2)).usPrivacy("1YNN").build()
        assertEquals(
            regulatory,
            Regulatory {
                gdpr = true
                gdprConsent = "tc"
                coppa = false
                gpp = "g"
                gppSid = listOf(2)
                usPrivacy = "1YNN"
            },
        )
        assertEquals(regulatory.hashCode(), Regulatory.Builder().gdpr(true).gdprConsent("tc").coppa(false).gpp("g").gppSid(listOf(2)).usPrivacy("1YNN").build().hashCode())
        assertFalse("tc" in regulatory.toString())
    }

    @Test fun configurationDefaultsAndBuilder() {
        val configuration = Configuration.Builder("token").logLevel(Configuration.LogLevel.DEBUG).adServerUrl("https://staging.test").build()
        assertEquals(Configuration.LogLevel.DEBUG, configuration.logLevel)
        assertEquals(
            configuration,
            Configuration("token") {
                logLevel = Configuration.LogLevel.DEBUG
                adServerUrl = "https://staging.test"
            },
        )
        val defaults = Configuration("token")
        assertEquals(Configuration.DEFAULT_AD_SERVER_URL, defaults.adServerUrl)
        assertEquals(Configuration.LogLevel.WARNING, defaults.logLevel)
        assertNotEquals(configuration.hashCode(), defaults.hashCode())
        assertTrue("token" in defaults.toString())
    }

    @Test fun everyExceptionSaysWhatHappened() {
        val messages = listOf(
            VideoAdException.NotInitialized(), VideoAdException.InitializationFailed(), VideoAdException.Destroyed(),
            VideoAdException.Disabled("paused"), VideoAdException.NoFill(NoFillReason.FrequencyCap),
            VideoAdException.Request(VideoAdException.RequestFailure.Offline), VideoAdException.CreativeFailed(405), VideoAdException.AlreadyShown(),
            VideoAdException.AnotherAdShowing(), VideoAdException.Invalidated(VideoAd.InvalidationReason.Expired), VideoAdException.NotLoaded(),
            VideoAdException.ContentPaused(), VideoAdException.ContainerTooSmall(480, 120), VideoAdException.CannotShowNow(),
            VideoAdException.WrongShowMethod(),
        ).map { it.message!! }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.toSet().size)
        assertTrue("frequencyCap" in messages[4] && "405" in messages[6] && "480×120" in messages[12])
        assertEquals(VideoAdException.RequestFailure.Offline, VideoAdException.Request(VideoAdException.RequestFailure.Offline).failure)
    }

    @Test fun theValueTypesCompareByValue() {
        assertEquals(VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT), VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT))
        assertNotEquals(VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT), VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.ESTIMATED))
        assertEquals("Price(1.5 USD, EXACT)", VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT).toString())
        assertEquals(VideoAd.RejectReason.Outbid(2.0, "net"), VideoAd.RejectReason.Outbid(2.0, "net"))
        assertEquals(VideoAd.RejectReason.Outbid(2.0, "net").hashCode(), VideoAd.RejectReason.Outbid(2.0, "net").hashCode())
        assertEquals("Outbid(winningPrice=null, winner=null)", VideoAd.RejectReason.Outbid().toString())
        assertEquals("NotUsed", VideoAd.RejectReason.NotUsed.toString())
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(400), VideoAd.InvalidationReason.CreativeFailed(400))
        assertEquals(400, VideoAd.InvalidationReason.CreativeFailed(400).hashCode())
        assertEquals(
            listOf("Expired", "Rejected", "Destroyed", "CreativeFailed(1)"),
            listOf(
                VideoAd.InvalidationReason.Expired,
                VideoAd.InvalidationReason.Rejected,
                VideoAd.InvalidationReason.Destroyed,
                VideoAd.InvalidationReason.CreativeFailed(1),
            ).map { it.toString() },
        )
        assertEquals(VideoAd.Click(VideoAd.Click.Area.Other("cta")), VideoAd.Click(VideoAd.Click.Area.Other("cta")))
        assertEquals("Click(EndCard)", VideoAd.Click(VideoAd.Click.Area.EndCard).toString())
        assertEquals("Creative", VideoAd.Click.Area.Creative.toString())
        assertEquals(VideoAd.Click.Area.Other("a").hashCode(), VideoAd.Click.Area.Other("a").hashCode())
        assertEquals(NoFillReason.Other("x"), NoFillReason.Other("x"))
        assertEquals("x".hashCode(), NoFillReason.Other("x").hashCode())
        assertEquals(KontextVideo.State.Disabled("r"), KontextVideo.State.Disabled("r"))
        assertEquals("Disabled(r)", KontextVideo.State.Disabled("r").toString())
        assertEquals(
            listOf("NotInitialized", "Initializing", "Enabled", "Failed"),
            listOf(
                KontextVideo.State.NotInitialized,
                KontextVideo.State.Initializing,
                KontextVideo.State.Enabled,
                KontextVideo.State.Failed,
            ).map { it.toString() },
        )
        assertEquals("r".hashCode(), KontextVideo.State.Disabled("r").hashCode())
    }

    @Test fun aPlayerNeedsOnlyItsPositionAndPlayingState() {
        val player = object : ContentPlayer {
            override val currentPositionMillis = 1L
            override val isPlaying = true
        }
        assertNull(player.playbackSpeed)
        val listener = ContentPlayer.Listener { }
        player.addListener(listener) // no-ops by default
        player.removeListener(listener)
        listener.onSeek()
        listener.onContentChanged()
    }

    @Test fun formatsHaveTheirWireNames() {
        assertEquals(listOf("overlay", "pause", "linear"), AdFormat.entries.map { it.wireName })
        assertEquals(AdFormat.PAUSE, AdFormat.fromWire("pause"))
        assertNull(AdFormat.fromWire("banner"))
    }
}
