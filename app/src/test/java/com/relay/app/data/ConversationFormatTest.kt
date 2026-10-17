package com.relay.app.data

import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.data.conversation.ConversationSummary
import com.relay.app.data.conversation.ConversationSummary.Kind
import com.relay.app.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ConversationFormatTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val locale = Locale.ENGLISH
    // Monday 2026-09-21 15:30 local time
    private val now = ZonedDateTime.of(2026, 9, 21, 15, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun label(ts: Long) = ConversationFormat.timeLabel(ts, now, zone, locale)

    @Test
    fun previewOfEachType() {
        assertEquals("hello there", ConversationFormat.preview(MessageType.TEXT, "hello there", false))
        assertEquals("You: hello there", ConversationFormat.preview(MessageType.TEXT, "hello there", true))
        assertEquals("Shared a location", ConversationFormat.preview(MessageType.LOCATION, "TYPE:LOCATION|...", false))
        assertEquals("You: Photo", ConversationFormat.preview(MessageType.IMAGE, "", true))
        assertEquals("Video", ConversationFormat.preview(MessageType.VIDEO, "cap", false))
        assertEquals("Asked for your location", ConversationFormat.preview(MessageType.LOCATION_REQUEST, "", false))
        assertEquals("You asked for their location", ConversationFormat.preview(MessageType.LOCATION_REQUEST, "", true))
        assertEquals("Declined your location request", ConversationFormat.preview(MessageType.LOCATION_DECLINED, "", false))
        assertEquals("You declined a location request", ConversationFormat.preview(MessageType.LOCATION_DECLINED, "", true))
        assertEquals("No messages yet", ConversationFormat.preview(null, null, false))
    }

    @Test
    fun previewDoesNotLeakProtocolStrings() {
        // The stored body of a pin/request is the raw TYPE: string; the list must never show it.
        val p = ConversationFormat.preview(MessageType.LOCATION, "TYPE:LOCATION|LAT:1.0|LNG:2.0", true)
        assertFalse(p.contains("TYPE:"))
    }

    @Test
    fun previewCollapsesWhitespaceAndCapsLength() {
        assertEquals("a b c", ConversationFormat.preview(MessageType.TEXT, "  a\n\n b\t c  ", false))
        val long = ConversationFormat.preview(MessageType.TEXT, "x".repeat(500), false)
        assertTrue(long.length <= 80)
        assertTrue(long.endsWith("…"))
        assertEquals("Message", ConversationFormat.preview(MessageType.TEXT, "   ", false))
    }

    @Test
    fun timeLabelTodayYesterdayWeekdayDateYear() {
        assertEquals("09:05", label(at(2026, 9, 21, 9, 5)))
        assertEquals("Yesterday", label(at(2026, 9, 20, 23, 59)))
        assertEquals("Fri", label(at(2026, 9, 18)))   // 3 days ago
        assertEquals("Tue", label(at(2026, 9, 15)))   // 6 days ago, still a weekday
        assertEquals("14 Sep", label(at(2026, 9, 14))) // 7 days ago: a date
        assertEquals("3 Mar", label(at(2026, 3, 3)))
        assertEquals("3 Mar 2025", label(at(2025, 3, 3)))
    }

    @Test
    fun timeLabelHandlesMidnightBoundaryAndSkewAndEmpty() {
        assertEquals("00:00", label(at(2026, 9, 21, 0, 0)))
        assertEquals("Yesterday", label(at(2026, 9, 20, 0, 0)))
        assertEquals("16:00", label(at(2026, 9, 21, 16, 0)))  // slightly in the future (clock skew): treated as today
        assertEquals("09:00", label(at(2026, 9, 22, 9, 0)))   // a day ahead: still shown as a time, never negative days
        assertEquals("", label(0L))
    }

    private fun conv(kind: Kind, id: Long, name: String, ts: Long, type: MessageType? = MessageType.TEXT) =
        ConversationSummary(kind, id, name, type, if (type != null) "x" else null, ts, false, 0, null, 0)

    @Test
    fun sortsNewestFirstAndSinksEmptyConversationsAlphabetically() {
        val sorted = ConversationFormat.sorted(
            listOf(
                conv(Kind.GROUP, 1, "zeta group", 0, type = null),
                conv(Kind.CONTACT, 2, "Old", 100),
                conv(Kind.GROUP, 3, "alpha group", 0, type = null),
                conv(Kind.CONTACT, 4, "New", 300),
                conv(Kind.GROUP, 5, "Mid", 200),
            )
        )
        assertEquals(listOf("New", "Mid", "Old", "alpha group", "zeta group"), sorted.map { it.name })
    }

    @Test
    fun equalTimestampsAreOrderedDeterministically() {
        val a = conv(Kind.CONTACT, 2, "b", 100)
        val b = conv(Kind.CONTACT, 1, "a", 100)
        assertEquals(listOf(b, a), ConversationFormat.sorted(listOf(a, b)))
        assertEquals(listOf(b, a), ConversationFormat.sorted(listOf(b, a)))
    }

    @Test
    fun requestRules() {
        assertTrue(ConversationFormat.isRequest(hasNostrKey = true, verifiedInPerson = false, blocked = false, hasReceivedMessage = true))
        assertFalse("verified in person", ConversationFormat.isRequest(true, true, false, true))
        assertFalse("blocked", ConversationFormat.isRequest(true, false, true, true))
        assertFalse("nothing received from them", ConversationFormat.isRequest(true, false, false, false))
        assertFalse("legacy contact without a key", ConversationFormat.isRequest(false, false, false, true))
    }

    @Test
    fun badgeCapsAtNinetyNinePlus() {
        assertEquals("1", ConversationFormat.badge(1))
        assertEquals("99", ConversationFormat.badge(99))
        assertEquals("99+", ConversationFormat.badge(100))
        assertEquals("99+", ConversationFormat.badge(5000))
    }
}
