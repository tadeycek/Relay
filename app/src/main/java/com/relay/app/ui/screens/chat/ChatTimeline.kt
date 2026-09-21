package com.relay.app.ui.screens.chat

import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One row of the chat list, after messages are grouped and separators are inserted. */
sealed class ChatItem {
    abstract val key: String

    /** "Today", "Yesterday", "Monday", "14 September". */
    data class Day(val label: String, val date: LocalDate) : ChatItem() {
        override val key get() = "day_$date"
    }

    /** Where the messages you had not seen begin. */
    data class UnreadDivider(val count: Int) : ChatItem() {
        override val key get() = "unread"
    }

    /** A system event shown as a centred line, not a bubble (location requests and replies). */
    data class Note(val message: Message) : ChatItem() {
        override val key get() = "note_${message.id}"
    }

    /**
     * A message bubble. [startsGroup] / [endsGroup] say whether it opens or closes a run of consecutive
     * messages from the same person, which decides corner tightness, spacing and where the time shows.
     */
    data class Bubble(val message: Message, val startsGroup: Boolean, val endsGroup: Boolean) : ChatItem() {
        override val key get() = "m_${message.id}"
    }
}

/** Pure list-building rules for the chat screen, unit-tested without Android. */
object ChatTimeline {

    /** Messages from the same sender closer together than this stay in one visual group. */
    const val GROUP_GAP_MS = 5 * 60 * 1000L

    fun build(
        messages: List<Message>,
        firstUnreadId: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<ChatItem> {
        val items = ArrayList<ChatItem>(messages.size + 4)
        var lastDay: LocalDate? = null
        var previous: Message? = null
        var dividerAdded = false

        messages.forEachIndexed { index, m ->
            val day = Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalDate()
            if (day != lastDay) {
                items.add(ChatItem.Day(dayLabel(m.timestamp, nowMs, zone, locale), day))
                lastDay = day
                previous = null
            }
            if (!dividerAdded && firstUnreadId != null && m.id == firstUnreadId) {
                val unreadCount = messages.drop(index).count { !it.isSent && it.type.countsAsUnread() }
                items.add(ChatItem.UnreadDivider(unreadCount))
                dividerAdded = true
                previous = null
            }
            if (m.type == MessageType.LOCATION_REQUEST || m.type == MessageType.LOCATION_DECLINED) {
                items.add(ChatItem.Note(m))
                previous = null
                return@forEachIndexed
            }
            val starts = previous == null ||
                previous!!.isSent != m.isSent ||
                m.timestamp - previous!!.timestamp > GROUP_GAP_MS
            items.add(ChatItem.Bubble(m, startsGroup = starts, endsGroup = false))
            previous = m
        }

        // A bubble ends its group when the next row is not a bubble or begins a new group.
        for (i in items.indices) {
            val current = items[i] as? ChatItem.Bubble ?: continue
            val next = items.getOrNull(i + 1)
            val ends = next !is ChatItem.Bubble || next.startsGroup
            if (ends) items[i] = current.copy(endsGroup = true)
        }
        return items
    }

    private fun MessageType.countsAsUnread() = this != MessageType.LOCATION_DECLINED && this != MessageType.LOCATION_REQUEST

    fun dayLabel(timestampMs: Long, nowMs: Long, zone: ZoneId, locale: Locale): String {
        val t = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days <= 0L -> "Today" // includes small clock skew into the future
            days == 1L -> "Yesterday"
            days < 7L -> DateTimeFormatter.ofPattern("EEEE", locale).format(t)
            t.year == now.year -> DateTimeFormatter.ofPattern("d MMMM", locale).format(t)
            else -> DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(t)
        }
    }

    /** "Expires in 3 h", "Expires in 12 min", or null when the message never expires. */
    fun expiryLabel(expiryAtMs: Long?, nowMs: Long): String? {
        if (expiryAtMs == null) return null
        val remaining = expiryAtMs - nowMs
        if (remaining <= 0L) return "Expired"
        val minutes = (remaining + 59_999L) / 60_000L
        return when {
            minutes < 60L -> "Expires in $minutes min"
            minutes < 48L * 60L -> "Expires in ${(minutes + 59L) / 60L} h"
            else -> "Expires in ${(minutes + 1439L) / 1440L} days"
        }
    }
}
