package com.relay.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadCodecTest {

    @Test
    fun roundTripsTextIncludingUnicodeAndProtocolStrings() {
        val bodies = listOf(
            "hello",
            "Ćevapi za večerjo 🍽️ — 你好",
            "TYPE:LOCATION|LAT:46.056900|LNG:14.505800|EXPIRY:1hr|LABEL:Café",
            "line1\nline2\t\"quoted\" \\ backslash",
            "",
        )
        for (b in bodies) {
            val p = RelayPayload(id = "abc-123", ts = 1_758_440_000_000L, body = b)
            assertEquals(p, PayloadCodec.decode(PayloadCodec.encode(p)))
        }
    }

    @Test
    fun rejectsMalformedJsonAndMissingFields() {
        assertNull(PayloadCodec.decode("not json"))
        assertNull(PayloadCodec.decode(""))
        assertNull(PayloadCodec.decode("{}"))
        assertNull(PayloadCodec.decode("""{"v":1,"id":"x","ts":1}"""))
        assertNull(PayloadCodec.decode("""{"v":1,"id":"x","body":"b"}"""))
        assertNull(PayloadCodec.decode("""{"id":"x","ts":1,"body":"b"}"""))
    }

    @Test
    fun rejectsUnsupportedVersions() {
        assertNull(PayloadCodec.decode("""{"v":0,"id":"x","ts":1,"body":"b"}"""))
        assertNull(PayloadCodec.decode("""{"v":${PayloadCodec.CURRENT_VERSION + 1},"id":"x","ts":1,"body":"b"}"""))
        assertNotNull(PayloadCodec.decode("""{"v":${PayloadCodec.CURRENT_VERSION},"id":"x","ts":1,"body":"b"}"""))
    }

    @Test
    fun rejectsEmptyOrOversizedIdAndOversizedBody() {
        assertNull(PayloadCodec.decode("""{"v":1,"id":"","ts":1,"body":"b"}"""))
        val longId = "a".repeat(PayloadCodec.MAX_ID_LENGTH + 1)
        assertNull(PayloadCodec.decode("""{"v":1,"id":"$longId","ts":1,"body":"b"}"""))
        val okId = "a".repeat(PayloadCodec.MAX_ID_LENGTH)
        assertNotNull(PayloadCodec.decode("""{"v":1,"id":"$okId","ts":1,"body":"b"}"""))
        val hugeBody = "x".repeat(PayloadCodec.MAX_BODY_LENGTH + 1)
        assertNull(PayloadCodec.decode("""{"v":1,"id":"x","ts":1,"body":"$hugeBody"}"""))
    }

    @Test
    fun ignoresUnknownExtraFieldsForForwardCompatibility() {
        val p = PayloadCodec.decode("""{"v":1,"id":"x","ts":5,"body":"b","future":"field"}""")
        assertEquals(RelayPayload("x", 5, "b"), p)
    }

    @Test
    fun newIdsAreUniqueAndWithinLimit() {
        val a = PayloadCodec.newId()
        val b = PayloadCodec.newId()
        assertNotEquals(a, b)
        assert(a.length <= PayloadCodec.MAX_ID_LENGTH)
    }
}
