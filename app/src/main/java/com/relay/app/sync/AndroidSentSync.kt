package com.relay.app.sync

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.util.Log
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.groups.GroupProtocol
import com.relay.app.groups.SharedGroups
import com.relay.app.media.MediaBody
import com.relay.app.messaging.AndroidInnerEnvelope
import com.relay.app.messaging.MessageDeletion
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.RetentionPolicy
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.Transports
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.MailboxTransport
import com.relay.app.transport.mailbox.PhoneDeviceEnrollment
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/**
 * Android wiring for the SENT-copy sync. All decisions live in [SentSyncPublisher] and
 * [SentSyncApplier]; this file is the thin hooks (staging, confirming, ticking) plus the SQLCipher,
 * SharedPreferences and signed-HTTP adapters for their ports. Compile-checked only.
 */
object SentSync {

    private const val TAG = "SentSync"
    private const val PREFS = "relay_sent_sync"
    private const val KEY_LINKED = "linked_hint"
    private const val KEY_CURSOR = "cursor"
    private const val MIN_TICK_GAP_MS = 15_000L

    private val tickLock = Mutex()
    private val lastTick = AtomicLong(0)

    fun hint(context: Context): LinkedHint = object : LinkedHint {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get() = prefs.getBoolean(KEY_LINKED, false)
        override fun set(value: Boolean) { prefs.edit().putBoolean(KEY_LINKED, value).apply() }
    }

    /** Called when the phone learns how many computers are linked (after linking, or on the Linked computers list). */
    fun noteLinkedCount(context: Context, count: Int) {
        val hint = hint(context)
        if (hint.get() != (count > 0)) hint.set(count > 0)
    }

    // ---- staging hooks (no network) -----------------------------------------------------------------

    /** A user-visible message was queued for [recipientPubkey]; call in the same transaction as its outbox row. */
    fun stageDirect(context: Context, recipientPubkey: String, payloadId: String, ts: Long, plainBody: String) {
        if (!hint(context).get() || !SentSyncWire.mirrored(plainBody)) return
        stage(context, recipientPubkey, payloadId, ts, plainBody)
    }

    /** A group post was queued; the SENT copy carries the group packet itself, keyed by the group event id. */
    fun stageGroup(context: Context, state: GroupProtocol.State, packet: GroupProtocol.Event) {
        if (!hint(context).get() || packet.kind != "post") return
        stage(context, "group:${state.owner}:${state.id}", packet.event, packet.time, GroupProtocol.format(packet))
    }

    private fun stage(context: Context, target: String, payloadId: String, ts: Long, plain: String) {
        try {
            val json = SentSyncWire.selfSealedPayload(payloadId, ts, plain) { body ->
                RelayCrypto.myPublicKeyBase64(context)?.let { AndroidInnerEnvelope.seal(it, body) }
            } ?: return
            AndroidSyncOutbox(MessagingDb.get(context)).enqueue(SyncOutboxItem(payloadId, target, json, System.currentTimeMillis()))
        } catch (e: Exception) {
            // Mirroring is best effort: it must never fail or delay the user's own send.
            Log.w(TAG, "couldn't stage a sent copy: ${e.javaClass.simpleName}")
        }
    }

    /** The mailbox accepted the message with this payload id (or, for a group, one of its legs). */
    fun confirmed(context: Context, payloadId: String) {
        if (!hint(context).get()) return
        runCatching {
            val db = MessagingDb.get(context)
            val outbox = AndroidSyncOutbox(db)
            outbox.confirm(payloadId)
            db.readableDatabase.rawQuery("SELECT event FROM group_delivery WHERE payload_id=?", arrayOf(payloadId))
                .use { c -> while (c.moveToNext()) outbox.confirm(c.getString(0)) }
        }.onFailure { Log.w(TAG, "couldn't confirm a sent copy: ${it.javaClass.simpleName}") }
        com.relay.app.messaging.MessagingRuntime.launchIo { tick(context) }
    }

    /** The send failed for good: its sent copy must never be published. */
    fun failed(context: Context, payloadId: String) {
        if (!hint(context).get()) return
        runCatching { AndroidSyncOutbox(MessagingDb.get(context)).discard(payloadId) }
    }

    // ---- periodic work -------------------------------------------------------------------------------

    /** Publishes confirmed copies, then applies what other devices published. At most one run at a time. */
    suspend fun tick(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val hint = hint(app)
        if (!hint.get() || !MailboxConfig.available) return
        val now = System.currentTimeMillis()
        val last = lastTick.get()
        if (!force && now - last < MIN_TICK_GAP_MS) return
        val mailbox = Transports.get(app) as? MailboxTransport ?: return
        if (!tickLock.tryLock()) return
        try {
            lastTick.set(now)
            val net = MailboxSyncNetwork(mailbox) { PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) } }
            val db = MessagingDb.get(app)
            SentSyncPublisher(net, AndroidSyncOutbox(db), hint).flush()
            val store = AndroidSentCopyStore(app, db)
            SentSyncApplier(net, store, AndroidSyncCursor(app), hint).poll()
            store.notifyTouched()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync tick failed: ${e.javaClass.simpleName}")
        } finally {
            tickLock.unlock()
        }
    }

    internal class AndroidSyncCursor(context: Context) : SyncCursorStore {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get() = prefs.getLong(KEY_CURSOR, 0L)
        // commit(), not apply(): the value must be on disk before the server is told to forget the events.
        override fun set(value: Long) { check(prefs.edit().putLong(KEY_CURSOR, value).commit()) { "cursor not persisted" } }
    }
}

/** `sent_sync_outbox` in the encrypted app database (schema v25). */
class AndroidSyncOutbox(private val db: RelayDbHelper) : SyncOutbox {

    override fun enqueue(item: SyncOutboxItem): Boolean {
        val values = ContentValues().apply {
            put("payload_id", item.payloadId); put("target", item.target); put("payload_json", item.payloadJson)
            put("created_at", item.createdAt); put("confirmed", if (item.confirmed) 1 else 0)
            put("attempts", item.attempts); put("next_attempt_at", item.nextAttemptAt)
        }
        return db.writableDatabase.insertWithOnConflict(TABLE, null, values, net.sqlcipher.database.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    override fun confirm(payloadId: String) {
        db.writableDatabase.execSQL("UPDATE $TABLE SET confirmed=1 WHERE payload_id=?", arrayOf(payloadId))
    }

    override fun discard(payloadId: String) {
        db.writableDatabase.delete(TABLE, "payload_id=?", arrayOf(payloadId))
    }

    override fun due(now: Long, limit: Int): List<SyncOutboxItem> =
        db.readableDatabase.rawQuery(
            "SELECT payload_id,target,payload_json,created_at,attempts,next_attempt_at FROM $TABLE WHERE confirmed=1 AND next_attempt_at<=? ORDER BY created_at LIMIT ?",
            arrayOf(now.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(SyncOutboxItem(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), true, c.getInt(4), c.getLong(5)))
            }
        }

    override fun recordAttempt(payloadId: String, attempts: Int, nextAttemptAt: Long) {
        db.writableDatabase.execSQL("UPDATE $TABLE SET attempts=?, next_attempt_at=? WHERE payload_id=?", arrayOf(attempts, nextAttemptAt, payloadId))
    }

    override fun remove(payloadId: String) = discard(payloadId)

    override fun purgeStale(olderThan: Long): Int =
        db.writableDatabase.delete(TABLE, "confirmed=0 AND created_at<?", arrayOf(olderThan.toString()))

    override fun clear() {
        db.writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        const val TABLE = "sent_sync_outbox"
        const val CREATE = """CREATE TABLE IF NOT EXISTS $TABLE (
            payload_id TEXT PRIMARY KEY,
            target TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            confirmed INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL DEFAULT 0
        )"""
    }
}

/** The sync routes over the mailbox's one signed-request path (NIP-98, device=primary). */
class MailboxSyncNetwork(
    private val mailbox: MailboxTransport,
    private val keys: () -> rust.nostr.sdk.Keys,
) : SyncNetwork {
    private val json = mapOf("Content-Type" to "application/json")

    override suspend fun listDevices(): DevicesResult {
        val r = mailbox.signedRequest("GET", "/v1/devices?device=primary", keys = keys())
        return SentSyncWire.linkedCount(r.status, r.body)
    }

    override suspend fun post(envelopeJson: String): PostResult {
        val r = mailbox.signedRequest("POST", "/v1/sync?device=primary", envelopeJson.toByteArray(Charsets.UTF_8), json, keys())
        return SentSyncWire.postOutcome(r.status, r.errorCode)
    }

    override suspend fun list(after: Long, limit: Int): ListResult {
        val r = mailbox.signedRequest("GET", "/v1/sync?after=$after&limit=$limit&device=primary", keys = keys())
        return SentSyncWire.parseList(r.status, r.body)
    }

    override suspend fun ack(through: Long): Boolean =
        mailbox.signedRequest("POST", "/v1/sync/ack?device=primary", SentSyncWire.ackBody(through).toByteArray(Charsets.UTF_8), json, keys()).ok
}

/** Applies sent copies to the phone's SQLCipher database, in one transaction per event. */
class AndroidSentCopyStore(private val context: Context, private val db: RelayDbHelper) : SentCopyStore {

    private val contacts = ContactRepository(db)
    private val touchedContacts = linkedSetOf<Long>()
    private val touchedGroups = linkedSetOf<Long>()

    override fun findDirect(contactPubkey: String): ThreadRef? =
        contacts.findByNostrPubkeySync(contactPubkey)
            ?.takeIf { !it.isDeleted && it.trustLevel != ContactTrustLevel.BLOCKED }
            ?.let { ThreadRef(it.id, false) }

    override fun findGroup(ownerPubkey: String, sharedId: String): ThreadRef? =
        db.readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=? AND hidden=0", arrayOf(ownerPubkey, sharedId))
            .use { if (it.moveToFirst()) ThreadRef(it.getLong(0), true) else null }

    override fun hasOutgoing(thread: ThreadRef, payloadId: String): Boolean = db.readableDatabase.rawQuery(
        if (thread.group) "SELECT 1 FROM group_messages WHERE group_id=? AND event_id=? AND is_sent=1"
        else "SELECT 1 FROM messages WHERE contact_id=? AND msg_id=? AND is_sent=1",
        arrayOf(thread.id.toString(), payloadId),
    ).use { it.moveToFirst() }

    override fun open(body: String): String? =
        if (SmsMessageParser.isEncryptedMessage(body)) AndroidInnerEnvelope.open(context, body) else body

    override fun insertOutgoing(thread: ThreadRef, payload: RelayPayload, plainBody: String): Boolean {
        if (!RetentionPolicy.admissible(payload.ts, System.currentTimeMillis())) return true
        return if (thread.group) insertGroup(thread, payload, plainBody) else insertDirect(thread, payload, plainBody)
    }

    private fun insertDirect(thread: ThreadRef, payload: RelayPayload, plain: String): Boolean {
        if (!SentSyncWire.mirrored(plain)) return true // a control body never becomes a chat row
        val media = MediaBody.parse(plain)
        // The attachment itself is not fetched for a copy written on a computer: the thread gets the caption.
        val body = if (MediaBody.isMedia(plain)) media?.caption.orEmpty().ifEmpty { ATTACHMENT_NOTE } else plain
        val row = MessageRepository(db).insertMessageSync(
            Message(contactId = thread.id, body = body, type = MessageType.TEXT, isSent = true, timestamp = payload.ts,
                msgId = payload.id, senderVerified = true, deliveryState = DeliveryState.SENT, unread = false),
        )
        check(row != -1L) { "couldn't save the sent copy" }
        touchedContacts += thread.id
        return true
    }

    private fun insertGroup(thread: ThreadRef, payload: RelayPayload, plain: String): Boolean {
        val packet = GroupProtocol.parse(plain) as? GroupProtocol.Event ?: return true
        val own = NostrIdentity.publicKeyHex(context)
        if (packet.kind != "post" || packet.author != own) return true
        val stored = SharedGroups.get(db, thread.id) ?: return true
        if (stored.state.id != packet.group.id || stored.state.owner != packet.group.owner) return true
        if (hasOutgoing(thread, packet.event)) return true
        val sql = db.writableDatabase
        sql.execSQL("INSERT OR IGNORE INTO shared_group_seen(group_id,event) VALUES(?,?)", arrayOf(thread.id, "$own:${packet.event}"))
        val media = MediaBody.parse(packet.text)
        val row = GroupMessageRepository(db).insertMessageSync(
            GroupMessage(groupId = thread.id, body = if (MediaBody.isMedia(packet.text)) media?.caption.orEmpty().ifEmpty { ATTACHMENT_NOTE } else packet.text,
                eventId = packet.event, senderKey = own, senderName = stored.state.members.firstOrNull { it.key == own }?.name,
                type = MessageType.TEXT, isSent = true, timestamp = payload.ts, unread = false),
        )
        check(row != -1L) { "couldn't save the sent copy" }
        touchedGroups += thread.id
        return true
    }

    override fun mergeOutgoing(thread: ThreadRef, payloadId: String) {
        if (thread.group) return
        db.writableDatabase.execSQL(
            "UPDATE messages SET delivery_state=? WHERE contact_id=? AND msg_id=? AND is_sent=1 AND delivery_state=?",
            arrayOf(DeliveryState.SENT, thread.id, payloadId, DeliveryState.NONE),
        )
    }

    override fun <T> inTransaction(block: () -> T): T =
        // Same lock order as the other write paths (groups, then deletion), then one SQLCipher transaction.
        synchronized(SharedGroups) { synchronized(MessageDeletion) {
            val sql = db.writableDatabase
            sql.beginTransaction()
            try {
                val value = block()
                sql.setTransactionSuccessful()
                value
            } finally {
                sql.endTransaction()
            }
        } }

    fun notifyTouched() {
        touchedContacts.forEach {
            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply { putExtra("contact_id", it); setPackage(context.packageName) })
        }
        touchedGroups.forEach { SharedGroups.notify(context, it) }
        touchedContacts.clear(); touchedGroups.clear()
    }

    private companion object {
        const val ATTACHMENT_NOTE = "Attachment sent from your computer"
    }
}
