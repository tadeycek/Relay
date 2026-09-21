package com.relay.app.data.conversation

import com.relay.app.data.model.MessageType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the Messages tab: a one-to-one chat or a group, with its latest activity. */
data class ConversationSummary(
    val kind: Kind,
    val id: Long,
    val name: String,
    val lastType: MessageType?,
    val lastBody: String?,
    val lastTimestamp: Long,
    val lastIsSent: Boolean,
    val lastDeliveryState: Int,
    val lastReadAt: Long?,
    val unread: Int,
    /** Someone who knows our key and messaged us but was never scanned in person (contacts only). */
    val isRequest: Boolean = false,
    val memberCount: Int = 0,
    /** Seed for this conversation's glyph (a contact's key, number or name); empty for groups. */
    val glyphSeed: String = "",
    /** Contact only: has a Nostr key, and was scanned in person. Together they decide how the glyph is drawn. */
    val hasKey: Boolean = false,
    val verified: Boolean = false,
    /** Group only: seeds of up to four members, for the group's 2x2 glyph. */
    val memberSeeds: List<String> = emptyList(),
) {
    enum class Kind { CONTACT, GROUP }

    val hasMessages: Boolean get() = lastType != null
}

/**
 * Text and ordering rules for the conversation list. Pure (java.time only) so they are unit-tested
 * on the JVM without Android.
 */
object ConversationFormat {

    private const val PREVIEW_MAX = 80

    fun preview(type: MessageType?, body: String?, isSent: Boolean): String {
        if (type == null) return "No messages yet"
        val you = if (isSent) "You: " else ""
        return when (type) {
            MessageType.TEXT -> you + oneLine(body.orEmpty()).ifEmpty { "Message" }
            MessageType.LOCATION -> you + "Shared a location"
            MessageType.IMAGE -> you + "Photo"
            MessageType.VIDEO -> you + "Video"
            MessageType.LOCATION_REQUEST -> if (isSent) "You asked for their location" else "Asked for your location"
            MessageType.LOCATION_DECLINED -> if (isSent) "You declined a location request" else "Declined your location request"
        }
    }

    /** Collapses whitespace/newlines to single spaces and caps the length so one message cannot fill a row. */
    fun oneLine(s: String): String {
        val flat = s.replace(Regex("""\s+"""), " ").trim()
        return if (flat.length > PREVIEW_MAX) flat.take(PREVIEW_MAX - 1).trimEnd() + "…" else flat
    }

    /**
     * Right-hand timestamp: `14:05` today, `Yesterday`, a short weekday within the last week,
     * `3 Mar` within this year, else `3 Mar 2025`. Empty for a conversation with no messages.
     */
    fun timeLabel(
        timestampMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (timestampMs <= 0L) return ""
        val t: ZonedDateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val now: ZonedDateTime = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days < 0L -> DateTimeFormatter.ofPattern("HH:mm", locale).format(t) // clock skew: treat as today
            days == 0L -> DateTimeFormatter.ofPattern("HH:mm", locale).format(t)
            days == 1L -> "Yesterday"
            days < 7L -> DateTimeFormatter.ofPattern("EEE", locale).format(t)
            t.year == now.year -> DateTimeFormatter.ofPattern("d MMM", locale).format(t)
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(t)
        }
    }

    /**
     * Newest activity first; conversations with no messages (only groups) sink to the bottom,
     * alphabetically. Stable for equal timestamps by kind then id so rows do not jump around.
     */
    fun sorted(list: List<ConversationSummary>): List<ConversationSummary> =
        list.sortedWith(
            compareByDescending<ConversationSummary> { it.hasMessages }
                .thenByDescending { it.lastTimestamp }
                .thenBy { if (it.hasMessages) "" else it.name.lowercase() }
                .thenBy { it.kind }
                .thenBy { it.id }
        )

    /** Requests are unverified strangers who messaged us; blocked contacts never appear at all. */
    fun isRequest(hasNostrKey: Boolean, verifiedInPerson: Boolean, blocked: Boolean, hasReceivedMessage: Boolean): Boolean =
        hasNostrKey && !verifiedInPerson && !blocked && hasReceivedMessage

    /** Badge text: exact up to 99, then "99+". */
    fun badge(count: Int): String = if (count > 99) "99+" else count.toString()
}
