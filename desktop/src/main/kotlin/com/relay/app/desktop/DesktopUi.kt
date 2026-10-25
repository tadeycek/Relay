package com.relay.app.desktop

import com.relay.app.groups.GroupProtocol
import com.relay.app.media.ProfileNameBody
import com.relay.app.media.ProfilePhotoBody
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row in the conversation rail: a 1:1 chat or a group, with its unread badge and preview. */
data class RailItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val isGroup: Boolean,
    val unread: Int,
    val lastMessage: String?,
    val lastSender: String?,
    val lastTs: Long,
    val avatar: ByteArray?,
    val memberCount: Int?,
)

/** One rendered message row. [showHeader] collapses consecutive messages from the same sender. */
data class ThreadRow(
    val id: String,
    val text: String,
    val senderKey: String,
    val senderName: String,
    val outgoing: Boolean,
    val time: String,
    val showHeader: Boolean,
    val avatar: ByteArray?,
    val unsupported: Boolean,
    /** A valid `TYPE:MEDIA` reference: the thread renders a media bubble instead of [text]. */
    val media: Boolean = false,
    val caption: String? = null,
    /** A local call-log note (WP14). Only [DesktopUi.buildThread] sets it, from the call-log table, never from message text. */
    val callLog: Boolean = false,
)

data class MemberRow(val key: String, val name: String, val isOwner: Boolean, val isSelf: Boolean, val avatar: ByteArray?)

/** Everything the three panes render, rebuilt from the store whenever the engine commits. */
data class ChatUiState(
    val rail: List<RailItem> = emptyList(),
    val selected: RailItem? = null,
    val thread: List<ThreadRow> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    val selfName: String = "",
    val selfAvatar: ByteArray? = null,
    val draft: String = "",
    /** Bumped when the controller puts a draft back (failed send); the composer re-applies [draft] then. */
    val draftVersion: Long = 0,
    val status: String = "",
    val offline: Boolean = false,
    val canSend: Boolean = false,
    val error: String? = null,
    /** The selected conversation is a direct, eligible contact: the header shows a Call button (WP14). */
    val canCall: Boolean = false,
)

/**
 * Presentation rules shared by the composables and their tests. Nothing here touches the database or a
 * coroutine: titles, previews, grouping, clock labels and the quick-switcher filter are pure.
 */
object DesktopUi {

    const val UNSUPPORTED = "Unsupported message — update Relay to read this"
    const val FILE = "File attachment"
    const val PHOTO = "Photo"
    const val VIDEO = "Video"

    /**
     * What a stored body should show. Control bodies (profiles, group rosters, call signalling) are
     * never chat: they render as nothing. An unknown `TYPE:` body renders as a fixed sentence instead
     * of leaking wire framing into the thread, which is what an older peer would produce.
     */
    fun displayText(body: String): String = when {
        body.isBlank() -> ""
        body.startsWith("TYPE:FILE|") -> FILE
        body.startsWith("TYPE:MEDIA|") -> com.relay.app.media.MediaBody.parse(body)?.let { if (it.isVideo) VIDEO else PHOTO } ?: UNSUPPORTED
        body.startsWith("TYPE:") -> if (isControlBody(body)) "" else UNSUPPORTED
        else -> body
    }

    private fun isControlBody(body: String): Boolean =
        GroupProtocol.isControl(body) || GroupProtocol.PREFIX.isNotEmpty() && body.startsWith(GroupProtocol.PREFIX) ||
            ProfileNameBody.isControl(body) || ProfilePhotoBody.isControl(body) ||
            body.startsWith(DesktopGroups.GROUP_AVATAR_REQUEST) || body.startsWith("TYPE:CALL|") ||
            body.startsWith("TYPE:SYNC|") || body.startsWith("TYPE:TYPING|")

    /** A one-line rail preview, or "" for a control body that has nothing to preview. */
    fun preview(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val text = displayText(body)
        if (text.isEmpty()) return ""
        return if (text.length <= 60) text else text.take(59) + "…"
    }

    /** Up to two letters, diacritics folded so "Ábel" reads "AB" rather than an empty circle. */
    fun initials(name: String): String {
        val folded = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        if (folded.isEmpty()) return "?"
        val words = folded.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = when {
            words.size >= 2 -> words[0].take(1) + words[1].take(1)
            else -> words[0].take(2)
        }
        val result = letters.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return result.ifEmpty { "?" }
    }

    fun clockLabel(ts: Long, now: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    fun dayLabel(ts: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day)
        }
    }

    fun badge(count: Int): String = when {
        count <= 0 -> ""
        count > 99 -> "99+"
        else -> count.toString()
    }

    /**
     * Groups consecutive messages by sender: a new header appears for a new sender, a new outgoing
     * state, a gap over five minutes, or a new day. Only the header carries the clock.
     */
    fun buildThread(
        messages: List<DesktopMessage>,
        ownPubkey: String,
        nameOf: (String) -> String,
        avatarOf: (String) -> ByteArray?,
        now: Long,
        calls: List<DesktopCallRecord> = emptyList(),
        contactName: String = "",
    ): List<ThreadRow> {
        val sorted = messages.sortedBy { it.ts }
        val rows = ArrayList<ThreadRow>(sorted.size + calls.size)
        var previous: DesktopMessage? = null
        val pendingCalls = ArrayDeque(calls.sortedBy { it.atMs })
        fun callRow(call: DesktopCallRecord) = ThreadRow(
            // ":" is outside DELIVERY_ID ([A-Za-z0-9_-]), so no remote message id can ever equal a call-row key.
            id = "call:log:${call.callId}", text = DesktopCallLog.label(call, contactName), senderKey = call.peer,
            senderName = contactName, outgoing = !call.incoming, time = clockLabel(call.atMs, now), showHeader = false,
            avatar = null, unsupported = false, callLog = true,
        )
        for (message in sorted) {
            while (pendingCalls.isNotEmpty() && pendingCalls.first().atMs <= message.ts) { rows += callRow(pendingCalls.removeFirst()); previous = null }
            val sameSender = previous != null && previous.sender == message.sender && previous.outgoing == message.outgoing
            val closeInTime = previous != null && message.ts - previous.ts <= GROUP_WINDOW_MS
            val sameDay = previous != null && dayLabel(previous.ts, now) == dayLabel(message.ts, now)
            val header = !(sameSender && closeInTime && sameDay)
            val text = displayText(message.body)
            rows += ThreadRow(
                id = message.id,
                text = text,
                senderKey = message.sender,
                senderName = if (message.outgoing) "You" else nameOf(message.sender),
                outgoing = message.outgoing,
                time = if (header) clockLabel(message.ts, now) else "",
                showHeader = header,
                avatar = avatarOf(message.sender),
                unsupported = text == UNSUPPORTED,
                media = text == PHOTO || text == VIDEO,
                caption = if (text == PHOTO || text == VIDEO) com.relay.app.media.MediaBody.parse(message.body)?.caption else null,
            )
            previous = message
        }
        while (pendingCalls.isNotEmpty()) rows += callRow(pendingCalls.removeFirst())
        return uniqueKeys(rows)
    }

    /** LazyColumn keys must be unique or Compose throws on every composition; guarantee it whatever the inputs. */
    private fun uniqueKeys(rows: List<ThreadRow>): List<ThreadRow> {
        val seen = HashSet<String>(rows.size * 2)
        return rows.map { row ->
            var id = row.id
            var n = 2
            while (!seen.add(id)) id = "${row.id}#${n++}"
            if (id == row.id) row else row.copy(id = id)
        }
    }

    fun filterRail(items: List<RailItem>, query: String): List<RailItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }
    }

    /** Most recent activity first; a group with no messages yet stays at the bottom instead of vanishing. */
    fun sortedRail(items: List<RailItem>): List<RailItem> =
        items.sortedWith(compareByDescending<RailItem> { it.lastTs }.thenBy { it.title.lowercase(Locale.ROOT) })

    fun railTitle(
        conversationId: String,
        group: StoredGroup?,
        profile: DesktopProfile?,
        contactName: String?,
        fallback: String,
    ): String = when {
        isGroupConversation(conversationId) -> group?.name ?: fallback
        profile?.name?.isNotBlank() == true -> profile.name!!
        contactName?.isNotBlank() == true -> contactName
        else -> fallback
    }

    fun memberRows(group: StoredGroup, ownPubkey: String, profileOf: (String) -> DesktopProfile?): List<MemberRow> =
        group.members.map { member ->
            MemberRow(
                key = member.key,
                name = profileOf(member.key)?.name?.takeIf { it.isNotBlank() } ?: member.name,
                isOwner = member.key == group.owner,
                isSelf = member.key == ownPubkey,
                avatar = profileOf(member.key)?.avatar,
            )
        }

    private const val GROUP_WINDOW_MS = 5 * 60 * 1000L
}
