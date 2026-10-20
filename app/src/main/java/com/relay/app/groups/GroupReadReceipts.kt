package com.relay.app.groups

import android.content.Context
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.GroupMessage
import com.relay.app.messaging.ActiveChat
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.messaging.ReadReceiptBody
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.UUID

object GroupReadReceipts {
    const val CREATE = """CREATE TABLE group_reads (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, reader TEXT NOT NULL, read_at INTEGER NOT NULL,
        PRIMARY KEY(group_id,event,reader))"""
    const val CREATE_SENT = """CREATE TABLE group_read_sent (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, author TEXT NOT NULL, PRIMARY KEY(group_id,event,author))"""
    data class State(val count: Int, val expected: Int, val readAt: Long?)

    fun send(context: Context, group: Long, messages: List<GroupMessage>) = synchronized(SharedGroups) {
        if (!RelayPreferences(context).readReceipts || !ActiveChat.foreground || ActiveChat.groupId != group) return@synchronized
        val db = MessagingDb.get(context)
        val stored = SharedGroups.get(db, group) ?: return@synchronized
        val own = NostrIdentity.publicKeyHex(context)
        if (stored.hidden || stored.state.members.none { it.key == own }) return@synchronized
        val recipients = SharedGroups.recipients(db, stored.state, own).associateBy { it.key }
        for ((author, received) in messages.filter { !it.isSent && it.eventId != null && it.senderKey != null }.groupBy { it.senderKey!! }) {
            val target = recipients[author] ?: continue
            // One lookup per author, not per message: this runs on every refresh of an open group.
            val alreadySent = db.readableDatabase.rawQuery("SELECT event FROM group_read_sent WHERE group_id=? AND author=?",
                arrayOf(group.toString(), author)).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val ids = received.mapNotNull { it.eventId }.distinct().filter { it !in alreadySent }
            for (chunk in ids.chunked(ReadReceiptBody.MAX_IDS)) {
                if (!ActiveChat.foreground || ActiveChat.groupId != group || !RelayPreferences(context).readReceipts) return@synchronized
                val packet = GroupProtocol.Event("read", GroupProtocol.ref(stored.state), UUID.randomUUID().toString(), own,
                    ReadReceiptBody.encodeIds(chunk), System.currentTimeMillis())
                val sql = db.writableDatabase
                sql.beginTransaction()
                try {
                    check(Outgoing.enqueueTo(context, target.key, target.inner, target.relays, target.contactId,
                        GroupProtocol.format(packet), payloadId = packet.event) != null)
                    for (event in chunk) sql.execSQL("INSERT OR IGNORE INTO group_read_sent(group_id,event,author) VALUES(?,?,?)", arrayOf(group,event,author))
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
        }
    }

    internal fun apply(context: Context, stored: SharedGroups.Stored, packet: GroupProtocol.Event) {
        val ids = ReadReceiptBody.parseIds(packet.text) ?: return
        val db = MessagingDb.get(context).writableDatabase
        val now = System.currentTimeMillis()
        var changed = false
        db.beginTransaction()
        try {
            for (event in ids) {
                db.execSQL("""INSERT OR IGNORE INTO group_reads(group_id,event,reader,read_at)
                    SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM group_messages WHERE group_id=? AND event_id=? AND is_sent=1)
                    AND (NOT EXISTS(SELECT 1 FROM group_delivery WHERE group_id=? AND event=? AND recipient IS NOT NULL)
                    OR EXISTS(SELECT 1 FROM group_delivery WHERE group_id=? AND event=? AND recipient=?))""",
                    arrayOf(stored.localId,event,packet.author,now,stored.localId,event,
                        stored.localId,event,stored.localId,event,packet.author))
                changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) > 0 } || changed
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) SharedGroups.notify(context, stored.localId)
    }

    fun states(db: RelayDbHelper, group: Long, events: List<String>): Map<String, State> = buildMap {
        for (chunk in events.distinct().chunked(400)) {
            if (chunk.isEmpty()) continue
            val marks = chunk.joinToString(",") { "?" }
            val args = (listOf(group.toString()) + chunk).toTypedArray()
            val reads = db.readableDatabase.rawQuery("SELECT event,COUNT(*),MAX(read_at) FROM group_reads WHERE group_id=? AND event IN ($marks) GROUP BY event", args)
                .use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1) to cursor.getLong(2)) } }
            db.readableDatabase.rawQuery("""SELECT d.event,COUNT(DISTINCT d.recipient),COUNT(DISTINCT r.reader),SUM(d.recipient IS NULL)
                FROM group_delivery d LEFT JOIN group_reads r ON r.group_id=d.group_id AND r.event=d.event AND r.reader=d.recipient
                WHERE d.group_id=? AND d.event IN ($marks) GROUP BY d.event""", args).use { cursor ->
                while (cursor.moveToNext()) {
                    val event = cursor.getString(0)
                    val expected = cursor.getInt(1)
                    val complete = expected > 0 && cursor.getInt(2) == expected && cursor.getInt(3) == 0
                    put(event, State(reads[event]?.first ?: 0, expected, if (complete) reads[event]?.second else null))
                }
            }
            for ((event, read) in reads) if (!containsKey(event)) put(event, State(read.first, 0, null))
        }
    }
}
