package com.relay.app.ui

import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.ui.screens.chat.ChatItem
import com.relay.app.ui.screens.chat.ChatTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatTimelineTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val locale = Locale.ENGLISH
    private val now = ZonedDateTime.of(2026, 9, 21, 15, 30, 0, 0, zone).toInstant().toEpochMilli() // a Monday

    private fun at(d: Int, h: Int, mi: Int, month: Int = 9, year: Int = 2026) =
        ZonedDateTime.of(year, month, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun msg(ts: Long, sent: Boolean, type: MessageType = MessageType.TEXT, unread: Boolean = false, id: Long = nextId++) =
        Message(id = id, contactId = 1, body = "x", type = type, isSent = sent, timestamp = ts, unread = unread)

    private fun build(list: List<Message>, firstUnread: Long? = null) =
        ChatTimeline.build(list, firstUnread, now, zone, locale)

    private fun bubbles(items: List<ChatItem>) = items.filterIsInstance<ChatItem.Bubble>()

    @Test
    fun emptyConversationHasNoRows() {
        assertTrue(build(emptyList()).isEmpty())
    }

    @Test
    fun aDaySeparatorPrecedesTheFirstMessageAndEachNewDay() {
        val items = build(listOf(msg(at(20, 9, 0), true), msg(at(21, 9, 0), false), msg(at(21, 9, 1), false)))
        val days = items.filterIsInstance<ChatItem.Day>()
        assertEquals(listOf("Yesterday", "Today"), days.map { it.label })
        assertTrue(items.first() is ChatItem.Day)
    }

    @Test
    fun dayLabelsCoverTodayYesterdayWeekdayDateAndYear() {
        fun label(ts: Long) = ChatTimeline.dayLabel(ts, now, zone, locale)
        assertEquals("Today", label(at(21, 0, 5)))
        assertEquals("Today", label(at(22, 9, 0))) // clock skew into the future never shows a negative day
        assertEquals("Yesterday", label(at(20, 23, 59)))
        assertEquals("Friday", label(at(18, 12, 0)))
        assertEquals("14 September", label(at(14, 12, 0)))
        assertEquals("3 March", label(at(3, 12, 0, month = 3)))
        assertEquals("3 March 2025", label(at(3, 12, 0, month = 3, year = 2025)))
    }

    @Test
    fun consecutiveMessagesFromOnePersonFormOneGroup() {
        val b = bubbles(build(listOf(msg(at(21, 9, 0), true), msg(at(21, 9, 1), true), msg(at(21, 9, 2), true))))
        assertEquals(listOf(true, false, false), b.map { it.startsGroup })
        assertEquals(listOf(false, false, true), b.map { it.endsGroup })
    }

    @Test
    fun aChangeOfSenderStartsANewGroup() {
        val b = bubbles(build(listOf(msg(at(21, 9, 0), true), msg(at(21, 9, 1), false), msg(at(21, 9, 2), true))))
        assertEquals(listOf(true, true, true), b.map { it.startsGroup })
        assertEquals(listOf(true, true, true), b.map { it.endsGroup })
    }

    @Test
    fun aLongGapSplitsAGroupButExactlyTheGapDoesNot() {
        val gap = ChatTimeline.GROUP_GAP_MS
        val t0 = at(21, 9, 0)
        val exactly = bubbles(build(listOf(msg(t0, true), msg(t0 + gap, true))))
        assertEquals(listOf(true, false), exactly.map { it.startsGroup })
        val over = bubbles(build(listOf(msg(t0, true), msg(t0 + gap + 1, true))))
        assertEquals(listOf(true, true), over.map { it.startsGroup })
    }

    @Test
    fun aDaySeparatorAlwaysBreaksAGroup() {
        val b = bubbles(build(listOf(msg(at(20, 23, 59), true), msg(at(21, 0, 1), true))))
        assertEquals(listOf(true, true), b.map { it.startsGroup })
        assertEquals(listOf(true, true), b.map { it.endsGroup })
    }

    @Test
    fun locationRequestsAndRepliesAreCentredNotesThatBreakGroups() {
        val items = build(
            listOf(
                msg(at(21, 9, 0), true),
                msg(at(21, 9, 1), false, MessageType.LOCATION_REQUEST),
                msg(at(21, 9, 2), true),
            )
        )
        assertEquals(1, items.count { it is ChatItem.Note })
        val b = bubbles(items)
        assertEquals(listOf(true, true), b.map { it.startsGroup })
        assertEquals(listOf(true, true), b.map { it.endsGroup })
    }

    @Test
    fun unreadDividerSitsBeforeTheFirstUnreadMessageAndCountsReceivedOnes() {
        val list = listOf(
            msg(at(21, 9, 0), false, id = 10),
            msg(at(21, 9, 1), false, unread = true, id = 11),
            msg(at(21, 9, 2), true, id = 12),
            msg(at(21, 9, 3), false, unread = true, id = 13),
        )
        val items = build(list, firstUnread = 11)
        val idx = items.indexOfFirst { it is ChatItem.UnreadDivider }
        assertTrue(idx > 0)
        assertEquals(2, (items[idx] as ChatItem.UnreadDivider).count) // messages 11 and 13; the sent one is not counted
        assertEquals(11L, (items[idx + 1] as ChatItem.Bubble).message.id)
    }

    @Test
    fun theDividerBreaksTheGroupSoNewMessagesStandApart() {
        val list = listOf(msg(at(21, 9, 0), false, id = 1), msg(at(21, 9, 1), false, unread = true, id = 2))
        val b = bubbles(build(list, firstUnread = 2))
        assertEquals(listOf(true, true), b.map { it.startsGroup })
    }

    @Test
    fun noDividerWhenNothingIsUnreadOrTheIdIsUnknown() {
        val list = listOf(msg(at(21, 9, 0), false, id = 1))
        assertFalse(build(list, firstUnread = null).any { it is ChatItem.UnreadDivider })
        assertFalse(build(list, firstUnread = 999).any { it is ChatItem.UnreadDivider })
    }

    @Test
    fun theDividerIsAddedOnlyOnce() {
        val list = listOf(msg(at(21, 9, 0), false, unread = true, id = 1), msg(at(21, 9, 1), false, unread = true, id = 2))
        assertEquals(1, build(list, firstUnread = 1).count { it is ChatItem.UnreadDivider })
    }

    @Test
    fun rowKeysAreUniqueSoTheListDoesNotReuseTheWrongRow() {
        val list = List(20) { msg(at(21, 9, it), it % 3 == 0, id = 100L + it) }
        val keys = build(list, firstUnread = 105).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun expiryLabels() {
        assertNull(ChatTimeline.expiryLabel(null, now))
        assertEquals("Expired", ChatTimeline.expiryLabel(now - 1, now))
        assertEquals("Expired", ChatTimeline.expiryLabel(now, now))
        assertEquals("Expires in 1 min", ChatTimeline.expiryLabel(now + 1, now))
        assertEquals("Expires in 59 min", ChatTimeline.expiryLabel(now + 59 * 60_000L, now))
        assertEquals("Expires in 1 h", ChatTimeline.expiryLabel(now + 60 * 60_000L, now))
        assertEquals("Expires in 6 h", ChatTimeline.expiryLabel(now + 6 * 3_600_000L, now))
        assertEquals("Expires in 2 days", ChatTimeline.expiryLabel(now + 48L * 3_600_000L, now))
    }
}
