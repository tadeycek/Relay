package com.relay.app.data.repository

import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.data.conversation.ConversationSummary
import com.relay.app.data.conversation.ConversationSummary.Kind
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.data.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the Messages tab: the latest message, unread count and request status of every chat and group
 * in two queries (one per kind) rather than one query per row.
 *
 * "Latest message" is found with a correlated subquery on (timestamp, _id) so a tie on timestamp still
 * picks exactly one row. Contacts appear only once there is a message; groups always appear, because a
 * freshly created group would otherwise have no way to be opened from this tab.
 */
class ConversationRepository(private val dbHelper: RelayDbHelper) {

    suspend fun getConversations(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        ConversationFormat.sorted(contactConversations() + groupConversations())
    }

    private fun contactConversations(): List<ConversationSummary> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT c._id, c.name, c.nostr_pubkey, c.qr_verified, c.trust_level,
                   m.type, m.body, m.timestamp, m.is_sent, m.delivery_state, m.read_at,
                   (SELECT COUNT(*) FROM messages u WHERE u.contact_id = c._id AND u.unread = 1),
                   (SELECT COUNT(*) FROM messages r WHERE r.contact_id = c._id AND r.is_sent = 0),
                   c.phone, c.deleted_at
            FROM contacts c
            INNER JOIN messages m ON m._id = (
                SELECT _id FROM messages WHERE contact_id = c._id ORDER BY timestamp DESC, _id DESC LIMIT 1
            )
            """.trimIndent(),
            null,
        )
        return cursor.use { c ->
            val list = mutableListOf<ConversationSummary>()
            while (c.moveToNext()) {
                val trust = ContactTrustLevel.fromDb(c.getString(4))
                if (trust == ContactTrustLevel.BLOCKED) continue
                list.add(
                    ConversationSummary(
                        kind = Kind.CONTACT,
                        id = c.getLong(0),
                        name = c.getString(1),
                        lastType = MessageType.fromDb(c.getString(5)),
                        lastBody = c.getString(6),
                        lastTimestamp = c.getLong(7),
                        lastIsSent = c.getInt(8) == 1,
                        lastDeliveryState = c.getInt(9),
                        lastReadAt = if (c.isNull(10)) null else c.getLong(10),
                        unread = c.getInt(11),
                        isRequest = ConversationFormat.isRequest(
                            hasNostrKey = !c.isNull(2),
                            verifiedInPerson = c.getInt(3) == 1,
                            blocked = false,
                            hasReceivedMessage = c.getInt(12) > 0,
                        ),
                        glyphSeed = seedFor(c.getString(2), c.getString(13), c.getString(1)),
                        hasKey = !c.isNull(2),
                        verified = c.getInt(3) == 1,
                        contactDeleted = !c.isNull(14),
                    )
                )
            }
            list
        }
    }

    /** Internet-only contacts store a "nostr:..." placeholder in the phone column; that is not a real number. */
    private fun seedFor(nostrPubkey: String?, phone: String?, name: String): String =
        GlyphGenerator.seedFor(nostrPubkey, phone?.takeUnless { it.startsWith(Contact.NOSTR_PHONE_PREFIX) || it.startsWith("deleted:") }, name)

    /** Up to four member seeds per group, in one query. */
    private fun memberSeedsByGroup(): Map<Long, List<String>> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT gm.group_id, c.nostr_pubkey, c.phone, c.name
            FROM group_members gm INNER JOIN contacts c ON c._id = gm.contact_id
            ORDER BY gm.group_id, c.name
            """.trimIndent(),
            null,
        )
        return cursor.use { c ->
            val map = HashMap<Long, MutableList<String>>()
            while (c.moveToNext()) {
                val list = map.getOrPut(c.getLong(0)) { mutableListOf() }
                if (list.size < 4) list.add(seedFor(c.getString(1), c.getString(2), c.getString(3)))
            }
            map
        }
    }

    private fun groupConversations(): List<ConversationSummary> {
        val memberSeeds = memberSeedsByGroup()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT g._id, g.name,
                   m.type, m.body, m.timestamp, m.is_sent,
                   (SELECT COUNT(*) FROM group_messages u WHERE u.group_id = g._id AND u.unread = 1),
                   (SELECT COUNT(*) FROM group_members gm WHERE gm.group_id = g._id)
            FROM groups g
            LEFT JOIN group_messages m ON m._id = (
                SELECT _id FROM group_messages WHERE group_id = g._id ORDER BY timestamp DESC, _id DESC LIMIT 1
            )
            """.trimIndent(),
            null,
        )
        return cursor.use { c ->
            val list = mutableListOf<ConversationSummary>()
            while (c.moveToNext()) {
                val hasMessage = !c.isNull(2)
                list.add(
                    ConversationSummary(
                        kind = Kind.GROUP,
                        id = c.getLong(0),
                        name = c.getString(1),
                        lastType = if (hasMessage) MessageType.fromDb(c.getString(2)) else null,
                        lastBody = if (hasMessage) c.getString(3) else null,
                        lastTimestamp = if (hasMessage) c.getLong(4) else 0L,
                        lastIsSent = hasMessage && c.getInt(5) == 1,
                        lastDeliveryState = 0,
                        lastReadAt = null,
                        unread = c.getInt(6),
                        memberCount = c.getInt(7),
                        glyphSeed = c.getString(1),
                        memberSeeds = memberSeeds[c.getLong(0)].orEmpty(),
                    )
                )
            }
            list
        }
    }

    /** Total unread across chats and groups, for the tab badge. */
    suspend fun totalUnread(): Int = withContext(Dispatchers.IO) {
        MessageRepository(dbHelper).totalUnreadSync() + GroupMessageRepository(dbHelper).totalUnreadSync()
    }
}
