package com.relay.app.groups

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.ConversationEventBody
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.messaging.RetentionPolicy
import com.relay.app.transport.nostr.NostrIdentity
import org.json.JSONArray
import java.util.UUID

object GroupDispatch {
    const val CREATE = """CREATE TABLE group_dispatch (
        payload_id TEXT PRIMARY KEY, group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, recipient TEXT NOT NULL, inner_key TEXT, relays TEXT NOT NULL,
        body TEXT NOT NULL, created_at INTEGER NOT NULL, requires_source INTEGER NOT NULL,
        next_attempt_at INTEGER NOT NULL DEFAULT 0)"""

    internal fun stage(context: Context, db: RelayDbHelper, to: SharedGroups.Recipient, group: Long, packet: GroupProtocol.Packet) {
        val event = (packet as? GroupProtocol.Event)?.event ?: UUID.randomUUID().toString()
        val payload = UUID.nameUUIDFromBytes("$group:$event:${to.key}".toByteArray(Charsets.UTF_8)).toString()
        val source = packet is GroupProtocol.Event && packet.kind == "post" &&
            ConversationEventBody.parse(packet.text)?.kind != "reaction"
        val sql = db.writableDatabase
        sql.insertWithOnConflict("group_dispatch", null, ContentValues().apply {
            put("payload_id", payload); put("group_id", group); put("event", event); put("recipient", to.key)
            put("inner_key", to.inner); put("relays", JSONArray(to.relays).toString())
            put("body", GroupProtocol.format(packet)); put("created_at", System.currentTimeMillis())
            put("requires_source", if (source) 1 else 0)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        check(sql.rawQuery("SELECT 1 FROM group_dispatch WHERE payload_id=?", arrayOf(payload)).use { it.moveToFirst() })
        if (packet is GroupProtocol.Event && packet.kind == "post")
            sql.execSQL("INSERT OR IGNORE INTO group_delivery(group_id,event,payload_id,state,recipient,requires_source) VALUES(?,?,?,?,?,?)",
                arrayOf(group, event, payload, DeliveryState.QUEUED, to.key, if (source) 1 else 0))
        com.relay.app.messaging.MessagingRuntime.kick()
    }

    fun drain(context: Context): Boolean {
        val db = MessagingDb.get(context)
        val pending = db.readableDatabase.rawQuery("SELECT payload_id FROM group_dispatch WHERE next_attempt_at<=? ORDER BY created_at LIMIT 32",
            arrayOf(System.currentTimeMillis().toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        for (payload in pending) {
            if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return false
            runCatching { dispatch(context, db, payload) }.onFailure {
                db.writableDatabase.execSQL("UPDATE group_dispatch SET next_attempt_at=? WHERE payload_id=?",
                    arrayOf(System.currentTimeMillis() + 5_000, payload))
                Log.e("GroupDispatch", "dispatch deferred", it)
            }
        }
        if (pending.size == 32) com.relay.app.messaging.MessagingRuntime.kick()
        return pending.isNotEmpty()
    }

    private fun readJob(db: RelayDbHelper, payload: String): Job? = db.readableDatabase.rawQuery(
        "SELECT group_id,event,recipient,inner_key,relays,body,created_at,requires_source FROM group_dispatch WHERE payload_id=?",
        arrayOf(payload)).use { cursor ->
        if (!cursor.moveToFirst()) null else Job(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
            cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getInt(7) != 0)
    }

    internal fun dispatch(context: Context, db: RelayDbHelper, payload: String,
        sealBody: (String?, String) -> String = com.relay.app.messaging.AndroidInnerEnvelope::seal) {
        val row = readJob(db, payload) ?: return
        val before = ContactRepository(db).findByNostrPubkeySync(row.recipient)
        val innerKey = before?.publicKey ?: row.inner
        val blockedBefore = before?.let { it.isDeleted || it.trustLevel == ContactTrustLevel.BLOCKED || it.pendingPublicKey != null } == true
        val prepared = if (blockedBefore || GroupProtocol.parse(row.body) == null ||
            RetentionPolicy.expired(row.created, System.currentTimeMillis())) null else {
            val relays = JSONArray(row.relays).let { values -> (0 until values.length()).map { values.getString(it) } }
            Outgoing.prepareTo(row.recipient, innerKey, relays, before?.id, row.body, payload, sealBody)
        }
        // Never hold the writer transaction or the group monitor while importing/sealing a key.
        // Recheck every deletion, membership and key condition after crypto, before committing.
        synchronized(SharedGroups) {
            val sql = db.writableDatabase
            sql.beginTransaction()
            try {
                val currentJob = readJob(db, payload)
                if (currentJob != null) {
                    check(currentJob == row) { "Group job changed during preparation" }
                    val stored = SharedGroups.get(db, row.group)
                    val packet = GroupProtocol.parse(row.body)
                    val own = NostrIdentity.publicKeyHex(context)
                    val contact = ContactRepository(db).findByNostrPubkeySync(row.recipient)
                    val blocked = contact?.let { it.isDeleted || it.trustLevel == ContactTrustLevel.BLOCKED || it.pendingPublicKey != null } == true
                    val member = stored?.state?.members?.any { it.key == row.recipient } == true
                    val removal = packet is GroupProtocol.Sync && stored?.state?.owner == own &&
                        packet.state.members.none { it.key == row.recipient }
                    val sourceExists = !row.requiresSource || sql.rawQuery(
                        "SELECT 1 FROM group_messages WHERE group_id=? AND event_id=? AND sender_key=? AND is_sent=1 AND timestamp>?",
                        arrayOf(row.group.toString(), row.event, own, RetentionPolicy.cutoff(System.currentTimeMillis()).toString())
                    ).use { it.moveToFirst() }
                    val allowed = stored != null && packet != null && !blocked && sourceExists &&
                        !com.relay.app.calculator.CalculatorOnly.enabled(context) &&
                        !RetentionPolicy.expired(row.created, System.currentTimeMillis()) &&
                        stored.state.members.any { it.key == own } && ((!stored.hidden && member) || removal)
                    if (allowed) {
                        checkNotNull(prepared) { "Recipient changed during preparation" }
                        check((contact?.publicKey ?: row.inner) == innerKey && contact?.id == prepared.contactId) {
                            "Recipient key changed during preparation"
                        }
                        check(Outgoing.enqueuePrepared(context, prepared) != null)
                    } else {
                        sql.execSQL("UPDATE group_delivery SET state=? WHERE payload_id=?", arrayOf(DeliveryState.FAILED, payload))
                        SharedGroups.notify(context, row.group)
                    }
                    sql.delete("group_dispatch", "payload_id=?", arrayOf(payload))
                }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
    }

    private data class Job(val group: Long, val event: String, val recipient: String, val inner: String?,
        val relays: String, val body: String, val created: Long, val requiresSource: Boolean)
}
