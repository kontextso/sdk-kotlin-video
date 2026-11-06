package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.internal.measurement.Tracker
import so.kontext.video.internal.measurement.TrackingMacros

class TrackingMacrosTest {
    @Test fun playheadsAreFormattedAndClamped() {
        assertEquals("00:00:00.000", TrackingMacros.playhead(-5))
        assertEquals("00:01:05.250", TrackingMacros.playhead(65_250))
        assertEquals("87600:00:00.000", TrackingMacros.playhead(Long.MAX_VALUE))
    }

    @Test fun macrosAreFoundWithTheirBracketsEncodedOrNot() {
        assertEquals("https://t.test/?ph=[MEDIAPLAYHEAD]&x=[OTHER]", TrackingMacros.decodingBrackets("https://t.test/?ph=%5BMEDIAPLAYHEAD%5d&x=%5bOTHER%5D"))
        assertTrue(TrackingMacros.asksForContentPlayhead("https://t.test/?ph=%5BCONTENTPLAYHEAD%5D"))
        assertTrue(TrackingMacros.asksForContentPlayhead("https://t.test/?ph=[MEDIAPLAYHEAD]"))
        assertFalse(TrackingMacros.asksForContentPlayhead("https://t.test/?ph=[ADPLAYHEAD]"))
    }

    @Test fun anIpv6HostKeepsItsBrackets() {
        val url = TrackingMacros.substitute("http://[::1]:8080/p?e=[ERRORCODE]&x=[UNKNOWN]", { 0L }, 0, 405, "f", null, null)
        assertEquals("http://[::1]:8080/p?e=405&x=%5BUNKNOWN%5D", url)
        val bare = TrackingMacros.substitute("https://[2001:db8::1]?e=[ERRORCODE]", { 0L }, 0, 400, "f", null, null)
        assertEquals("https://[2001:db8::1]?e=400", bare)
        assertEquals("http://[::1]/", TrackingMacros.substitute("http://%5B::1%5D/", { 0L }, 0, null, "f", null, null), "an encoded IPv6 host is a host")
        assertEquals("%5BX%5D?a=1", TrackingMacros.substitute("[X]?a=[ERRORCODE]", { 0L }, 0, 1, "f", null, null), "no scheme: every bracket encoded")
    }

    @Test fun aMacroInTheHostIsSubstituted() {
        val url = TrackingMacros.substitute("https://[CACHEBUSTING].tracker.test/p?e=[ERRORCODE]", { 0L }, 0, 400, "f", null, null)
        assertTrue(Regex("https://\\d{8}\\.tracker\\.test/p\\?e=400").matches(url), url)
        assertEquals("https://f.t.test/", TrackingMacros.substitute("https://%5BKONTEXT_FIREID%5D.t.test/", { 0L }, 0, null, "f", null, null))
        assertEquals("https://a.test:443/?c=00%3A00%3A02.000", TrackingMacros.substitute("https://a.test:443/?c=[MEDIAPLAYHEAD]", { 2_000L }, 0, null, "f", null, null))
        assertTrue(TrackingMacros.asksForContentPlayhead("https://[MEDIAPLAYHEAD].t.test/"))
    }

    @Test fun theContentPlayheadIsReadOnlyWhenTheUrlAsksForIt() {
        var reads = 0
        val playhead = {
            reads += 1
            65_250L
        }
        val url = TrackingMacros.substitute("https://t.test/?a=[ADPLAYHEAD]&e=[ERRORCODE]&x=[OTHER]", playhead, 1_000, 405, "f", null, null)
        assertEquals("https://t.test/?a=00%3A00%3A01.000&e=405&x=%5BOTHER%5D", url)
        assertEquals(0, reads)
        assertEquals("https://t.test/?c=00%3A01%3A05.250", TrackingMacros.substitute("https://t.test/?c=%5BCONTENTPLAYHEAD%5D", playhead, 0, null, "f", null, null))
        assertEquals(1, reads)
    }

    @Test fun aWinningPriceThatIsNotAPriceIsLeftEmpty() {
        fun price(value: Double) =
            TrackingMacros.substitute("https://t.test/?p=[KONTEXT_WINNINGPRICE]", { 0L }, 0, null, "f", null, so.kontext.video.internal.measurement.Tracker.NotUsed.Outbid(value, null))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) assertEquals("https://t.test/?p=", price(value), "$value")
        assertEquals("https://t.test/?p=2.5", price(2.5))
        assertEquals("https://t.test/?p=0.0", price(0.0))
    }

    @Test fun aWinningPriceIsWrittenAsTheSwiftSdkWritesIt() {
        // What Swift's String(Double) printed for each value.
        val swift = mapOf(
            3.0 to "3.0", 2.4 to "2.4", 0.0001 to "0.0001", 0.00001 to "1e-05", 0.0005 to "0.0005", 1e15 to "1000000000000000.0",
            9_007_199_254_740_992.0 to "9007199254740992.0", 9_007_199_254_740_994.0 to "9.007199254740994e+15", 1e16 to "1e+16",
            1e20 to "1e+20", 1.5e20 to "1.5e+20", 1.5e-7 to "1.5e-07", 123456789.123 to "123456789.123", -2.5 to "-2.5", 0.1 to "0.1",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf", 1e-300 to "1e-300",
            // Where the digits themselves are the hard part.
            1e23 to "1e+23", 5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308", 0.1 + 0.2 to "0.30000000000000004",
            9.5367431640625e-07 to "9.5367431640625e-07", 2.2250738585072014e-308 to "2.2250738585072014e-308", 1e22 to "1e+22",
            4.35e-05 to "4.35e-05", 1.23456789e15 to "1234567890000000.0", 2e-4 to "0.0002", 1.0e7 to "10000000.0", 123456.0 to "123456.0",
            1.1 to "1.1", 100.0 to "100.0", 0.00012345 to "0.00012345",
            Double.fromBits(0x43a4dbd1460f41bcL) to "7.515124997460372e+17", // where String.format rounded the last digit up
            // 2^-24: below a power of two the gap is half as wide; the nearest 16-digit decimal misses, its neighbour does not.
            Double.fromBits(0x3e70000000000000L) to "5.960464477539063e-08", -Double.fromBits(0x3e70000000000000L) to "-5.960464477539063e-08",
        )
        assertEquals(swift, swift.mapValues { (value, _) -> TrackingMacros.number(value) })
    }

    @Test fun aMacroWithNoValueIsEmptyAndEachValueIsItsOwn() {
        val template = "https://t.test/?e=[ERRORCODE]&d=[KONTEXT_VIEWDURATION]&r=[KONTEXT_REASON]&p=[KONTEXT_WINNINGPRICE]&w=[KONTEXT_WINNER]&f=[KONTEXT_FIREID]"
        fun filled(errorCode: Int?, viewDuration: Long?, notUsed: Tracker.NotUsed?) =
            TrackingMacros.substitute(template, { 0L }, 0, errorCode, "fid-1", viewDuration, notUsed)
        assertEquals("https://t.test/?e=&d=&r=&p=&w=&f=fid-1", filled(null, null, null))
        assertEquals("https://t.test/?e=402&d=0&r=notUsed&p=&w=&f=fid-1", filled(402, 0, Tracker.NotUsed.NotUsedByApp))
        assertEquals("https://t.test/?e=&d=12345&r=destroyed&p=&w=&f=fid-1", filled(null, 12_345, Tracker.NotUsed.Destroyed))
        assertEquals("https://t.test/?e=&d=&r=outbid&p=&w=&f=fid-1", filled(null, null, Tracker.NotUsed.Outbid(null, null)), "an outbid with no price or winner")
        assertEquals("https://t.test/?e=&d=&r=outbid&p=1e%2B20&w=a%26b%3Dc&f=fid-1", filled(null, null, Tracker.NotUsed.Outbid(1e20, "a&b=c")), "each value one query parameter")
    }

    @Test fun aMacroAppearingTwiceIsFilledEachTimeAndAMacroIsCaseSensitive() {
        val url = TrackingMacros.substitute("https://t.test/?a=[ERRORCODE]&b=[ERRORCODE]&c=[errorcode]", { 0L }, 0, 400, "f", null, null)
        assertEquals("https://t.test/?a=400&b=400&c=%5Berrorcode%5D", url)
    }

    @Test fun theTimestampIsTheDevicesTimeWithItsOffset() {
        val url = TrackingMacros.substitute("https://t.test/?t=[TIMESTAMP]", { 0L }, 0, null, "f", null, null)
        assertTrue(Regex("https://t\\.test/\\?t=\\d{4}-\\d{2}-\\d{2}T\\d{2}%3A\\d{2}%3A\\d{2}\\.\\d{3}(Z|%2B\\d{2}%3A\\d{2}|-\\d{2}%3A\\d{2})").matches(url), url)
    }

    @Test fun playheadsAtTheirBoundaries() {
        val table = listOf(
            0L to "00:00:00.000",
            999L to "00:00:00.999",
            59_999L to "00:00:59.999",
            3_599_999L to "00:59:59.999",
            3_600_000L to "01:00:00.000",
            360_000_000L to "100:00:00.000",
            TrackingMacros.MAX_PLAYHEAD_MILLIS to "87600:00:00.000",
            TrackingMacros.MAX_PLAYHEAD_MILLIS + 1 to "87600:00:00.000",
            Long.MIN_VALUE to "00:00:00.000",
        )
        for ((millis, expected) in table) assertEquals(expected, TrackingMacros.playhead(millis), "$millis")
    }

    @Test fun aValueKeepsOnlyTheUnreservedCharactersOfAUrl() {
        val table = listOf(
            "a b" to "a%20b", "a+b" to "a%2Bb", "a*b" to "a%2Ab", "a~b" to "a~b", "a-b_c.d" to "a-b_c.d", "a/b?c#d" to "a%2Fb%3Fc%23d",
            "é" to "%C3%A9", "%5B" to "%255B", "" to "",
        )
        for ((value, expected) in table) assertEquals(expected, TrackingMacros.encode(value), value)
    }

    @Test fun aNegativeZeroPriceIsWrittenAsSwiftWritesIt() {
        assertEquals("-0.0", TrackingMacros.number(-0.0))
        assertEquals("0.0", TrackingMacros.number(0.0))
    }
}
