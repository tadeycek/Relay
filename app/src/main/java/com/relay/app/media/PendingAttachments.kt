package com.relay.app.media

import android.content.Context
import android.content.Intent
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.model.*
import com.relay.app.data.repository.*
import com.relay.app.groups.*
import com.relay.app.messaging.*
import com.relay.app.transport.nostr.NostrIdentity
import java.io.File
import java.util.UUID

/** Local encrypted file + bubble + upload job commit together, before any network operation.
 * The process-wide worker resumes jobs after restart and never resurrects a deleted bubble. */
object PendingAttachments {
    const val CREATE = """CREATE TABLE pending_attachments (
        id TEXT PRIMARY KEY, target INTEGER NOT NULL, is_group INTEGER NOT NULL,
        row_id INTEGER NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, caption TEXT NOT NULL,
        created_at INTEGER NOT NULL, next_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)"""
    private data class Job(val id: String, val target: Long, val group: Boolean, val row: Long,
        val path: String, val mime: String, val caption: String, val created: Long, val attempts: Int)
    fun stage(context: Context, target: Long, group: Boolean, file: File, mime: String, caption: String) {
        val db = MessagingDb.get(context)
        val id = UUID.randomUUID().toString()
        val copy = MediaSender.keepEncryptedCopy(context, file)
        val sql = db.writableDatabase
        try {
            java.io.FileOutputStream(copy, true).use { it.fd.sync() }
            sql.beginTransaction()
            try {
                val type = if (mime.startsWith("image/")) MessageType.IMAGE else MessageType.VIDEO
                val now = System.currentTimeMillis()
                val row = if (group) {
                    val state = SharedGroups.get(db, target) ?: error("Group no longer exists")
                    val own = NostrIdentity.publicKeyHex(context)
                    check(!state.hidden && state.state.members.any { it.key == own }) { "You are no longer in this group" }
                    val row = GroupMessageRepository(db).insertMessageSync(GroupMessage(groupId = target, eventId = id,
                        senderKey = own, body = caption, type = type, mediaUri = copy.absolutePath, timestamp = now))
                    sql.execSQL("INSERT INTO group_delivery(group_id,event,payload_id) VALUES(?,?,?)", arrayOf(target, id, id))
                    row
                } else {
                    val contact = ContactRepository(db).getByIdSync(target) ?: error("Contact no longer exists")
                    check(!contact.isDeleted && contact.trustLevel != ContactTrustLevel.BLOCKED && contact.canUseInternetTransport) { "Pair with this contact before sending" }
                    MessageRepository(db).insertMessageSync(Message(contactId = target, body = caption, type = type,
                        mediaUri = copy.absolutePath, msgId = id, deliveryState = DeliveryState.QUEUED, timestamp = now))
                }
                check(row != -1L) { "Couldn't save attachment" }
                sql.execSQL("INSERT INTO pending_attachments(id,target,is_group,row_id,path,mime,caption,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(id, target, if (group) 1 else 0, row, copy.absolutePath, mime, caption, now))
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        } catch (e: Exception) { copy.delete(); throw e }
        changed(context, target, group)
    }
    private fun changed(context: Context, target: Long, group: Boolean) {
        if (group) SharedGroups.notify(context, target)
        else context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id", target))
    }
    private fun exists(context: Context, job: Job): Boolean = MessagingDb.get(context).readableDatabase.rawQuery(
        "SELECT 1 FROM ${if (job.group) "group_messages" else "messages"} WHERE _id=? AND ${if (job.group) "event_id" else "msg_id"}=?",
        arrayOf(job.row.toString(), job.id)).use { it.moveToFirst() }

    /** One worker only. Uploads run separately from text outbox dispatch. */
    suspend fun drain(context: Context) {
        val db = MessagingDb.get(context)
        // A linked origin's own confirmation is deferred until every SENT part has published grants.
        // Without a linked device there is no SENT publication to wait for.
        runCatching { confirmSentMedia(context, db) }
        val jobs = db.readableDatabase.rawQuery("SELECT id,target,is_group,row_id,path,mime,caption,created_at,attempts FROM pending_attachments WHERE next_at<=? ORDER BY created_at LIMIT 8",
            arrayOf(System.currentTimeMillis().toString())).use { c -> buildList {
                while (c.moveToNext()) add(Job(c.getString(0),c.getLong(1),c.getInt(2)!=0,c.getLong(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7),c.getInt(8)))
            } }
        for (job in jobs) {
            if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
            if (!exists(context, job)) {
                db.writableDatabase.delete("pending_attachments", "id=?", arrayOf(job.id))
                File(job.path).delete()
                continue
            }
            try {
                check(System.currentTimeMillis() - job.created < 7L * 24 * 60 * 60 * 1000) { "Attachment expired" }
                // Exactly who will get the message: only they may download the file from the server.
                val recipients = if (job.group) {
                    val stored = SharedGroups.get(db, job.target) ?: error("Group no longer exists")
                    SharedGroups.recipients(db, stored.state, NostrIdentity.publicKeyHex(context)).map { it.key }
                } else listOfNotNull(ContactRepository(db).getByIdSync(job.target)?.nostrPubkey)
                val ref = MediaSender.uploadBytes(context, RelayFileCrypto.readDecrypted(context, File(job.path)), job.mime, job.caption, recipients)
                // Keep lock ordering consistent with incoming/deletion and group send paths.
                synchronized(SharedGroups) { synchronized(MessageDeletion) {
                    val sql = db.writableDatabase
                    sql.beginTransaction()
                    try {
                        check(!com.relay.app.calculator.CalculatorOnly.enabled(context))
                        if (exists(context, job)) {
                            if (com.relay.app.transport.mailbox.MailboxConfig.available)
                                AndroidPhoneFileStore.persistRefs(db, job.id, listOf(ref.sha256Hex))
                            if (job.group) SharedGroups.send(context, job.target, MediaBody.format(ref), job.path, job.id)
                            else {
                                val contact = ContactRepository(db).getByIdSync(job.target) ?: error("Contact no longer exists")
                                check(!contact.isDeleted && contact.trustLevel != ContactTrustLevel.BLOCKED && contact.pendingPublicKey == null)
                                check(Outgoing.enqueue(context, contact, MediaBody.format(ref), payloadId = job.id) != null) { "Couldn't queue attachment" }
                            }
                            sql.delete("group_delivery", "payload_id=?", arrayOf(job.id))
                        }
                        sql.delete("pending_attachments", "id=?", arrayOf(job.id))
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                } }
                MessagingRuntime.kick()
            } catch (e: Exception) {
                if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
                val expired = System.currentTimeMillis() - job.created >= 7L * 24 * 60 * 60 * 1000
                if (expired) {
                    if (job.group) GroupDelivery.update(context, job.id, DeliveryState.FAILED)
                    else MessageRepository(db).setDeliveryStateSync(job.id, DeliveryState.FAILED)
                    db.writableDatabase.delete("pending_attachments", "id=?", arrayOf(job.id))
                } else db.writableDatabase.execSQL("UPDATE pending_attachments SET attempts=attempts+1,next_at=? WHERE id=?",
                    arrayOf(System.currentTimeMillis() + com.relay.app.transport.outbox.OutboxPolicy.nextDelayMs(job.attempts + 1), job.id))
            }
            changed(context, job.target, job.group)
        }
    }

    private fun confirmSentMedia(context: Context, db: com.relay.app.data.db.RelayDbHelper) {
        if (!com.relay.app.transport.mailbox.MailboxConfig.available) return
        val linked = com.relay.app.sync.SentSync.hint(context).get()
        val sent = com.relay.app.transport.outbox.OutboxState.SENT.dbValue
        val rows = db.readableDatabase.rawQuery("""SELECT f.payload_id,f.digests,f.sync_granted FROM file_media_refs f
            WHERE EXISTS(SELECT 1 FROM outbox o WHERE o.payload_id=f.payload_id AND o.state=?)
            AND NOT EXISTS(SELECT 1 FROM phone_files p WHERE p.payload_id=f.payload_id)
            LIMIT 8""", arrayOf(sent.toString())).use { c -> buildList {
            while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2) != 0))
        } }
        for ((id, digests, published) in rows) {
            if (!mediaOriginMayConfirm(true, linked, published)) continue
            // A pending copy must never be mistaken for an unlinked sender after the hint changes.
            if (db.readableDatabase.rawQuery("SELECT 1 FROM sent_sync_outbox WHERE payload_id=?", arrayOf(id)).use { it.moveToFirst() }) continue
            val refs = digests.split('\n').filter(String::isNotBlank)
            if (refs.size != 1 || !Regex("[0-9a-f]{64}").matches(refs.single())) continue
            val result = runCatching { phoneMedia(context).confirm(com.relay.app.transport.mailbox.MailboxMedia.urlFor(refs.single())) }.getOrNull()
            if (result is com.relay.app.transport.mailbox.MailboxMedia.Confirmation.Confirmed)
                db.writableDatabase.delete("file_media_refs", "payload_id=? AND digests=?", arrayOf(id, digests))
        }
    }
}
