package com.relay.app.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.relay.app.BuildConfig
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.files.*
import com.relay.app.messaging.*
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.mailbox.*
import com.relay.app.transport.nostr.NostrIdentity
import java.io.File

/** Send is deliberately opt-in; receiving never consults the rollout flag. Group file send is not enabled. */
object PhoneFiles {
    val sendEnabled: Boolean get() = BuildConfig.FILE_SEND_ENABLED && MailboxConfig.available
    private var instance: PhoneFileEngine? = null
    private var storeInstance: AndroidPhoneFileStore? = null
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    @Synchronized fun engine(context: Context): PhoneFileEngine {
        instance?.let { return it }
        val app = context.applicationContext
        val store = AndroidPhoneFileStore(app, MessagingDb.get(app))
        storeInstance = store
        val network = object : PhoneFileNetwork {
            @Volatile private var cancellation = MailboxMedia.Cancellation()
            private fun client() = MailboxMedia.Client(MailboxMedia.RequestContext(
                AndroidNostrSigner { PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) } }, "primary"))
            override fun upload(file: File, part: FilePart, recipients: List<String>) { val c=MailboxMedia.Cancellation(); cancellation=c; client().upload(file, part.sha256Hex, recipients, c) }
            override fun download(url: String, part: FilePart, target: File) { val c=MailboxMedia.Cancellation(); cancellation=c; client().downloadTo(url, part.sha256Hex, target, part.storedBytes.toLong(), c) }
            override fun confirm(url: String): Boolean { val c=MailboxMedia.Cancellation(); cancellation=c; return client().confirm(url,c) is MailboxMedia.Confirmation.Confirmed }
            override fun cancel() { cancellation.cancel() }
        }
        return PhoneFileEngine(PrivateFileSpace(File(app.filesDir, "file-transfers")), store, network,
            FileBody.urlBasePredicate(MailboxMedia::isOurs), {
                changes.value += 1
                app.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(app.packageName))
            }).also { instance = it }
    }
    fun store(context: Context): AndroidPhoneFileStore { engine(context); return requireNotNull(storeInstance) }
    fun send(context: Context, contactId: Long, uri: Uri, caption: String = ""): PhoneFileJob {
        check(sendEnabled) { "File sending is not enabled" }
        val contact = ContactRepository(MessagingDb.get(context)).getByIdSync(contactId)
            ?.takeIf { !it.isDeleted && it.qrVerified && it.canUseInternetTransport } ?: error("Verified internet contact required")
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        } ?: "attachment.bin"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        // Resolve the key BEFORE opening the stream: if it threw afterwards the opened SAF stream would leak.
        val own = NostrIdentity.publicKeyHex(context)
        val source = context.contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
        val job = engine(context).prepare(source, contactId, PayloadCodec.newId(), System.currentTimeMillis(), name, mime,
            MailboxConfig.url.trimEnd('/') + "/v1/media/", listOf(requireNotNull(contact.nostrPubkey), own), caption)
        MessagingRuntime.kick()
        return job
    }
    fun receive(context: Context, contactId: Long, payload: RelayPayload, sentCopy: Boolean = false): Boolean {
        // This also runs inside the SENT transaction; receiving does no network or final publication here.
        val job = engine(context).receive(payload, contactId, sentCopy) ?: return false
        MessagingRuntime.kick()
        return !job.cancelled
    }
    fun cleanup(context: Context) {
        val store=store(context)
        store.cancelMissing()
        engine(context).cleanup()
    }
    private val ticking = java.util.concurrent.atomic.AtomicBoolean()
    fun tick(context: Context) {
        if (!MailboxConfig.available || !ticking.compareAndSet(false,true)) return
        try { store(context).cancelMissing(); engine(context).tick() } finally { ticking.set(false) }
    }
    fun cancel(context: Context, token: String) { engine(context).cancel(token); MessagingRuntime.kick() }
    fun retry(context: Context, token: String) { engine(context).retry(token); MessagingRuntime.kick() }
}

/** Handwritten SQLCipher schema, intentionally independent of Room. Manifest is never updated in place. */
class AndroidPhoneFileStore(private val context: Context, private val db: RelayDbHelper) : PhoneFileStore {
    companion object {
        const val CREATE = """CREATE TABLE IF NOT EXISTS phone_files (
            token TEXT PRIMARY KEY, contact_id INTEGER NOT NULL REFERENCES contacts(_id) ON DELETE CASCADE,
            payload_id TEXT NOT NULL, timestamp INTEGER NOT NULL, wire TEXT NOT NULL,
            outgoing INTEGER NOT NULL, recipients TEXT NOT NULL, transferred INTEGER NOT NULL DEFAULT 0,
            confirmed INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0, queued INTEGER NOT NULL DEFAULT 0,
            cancelled INTEGER NOT NULL DEFAULT 0, error TEXT, UNIQUE(contact_id,payload_id))"""
        const val CREATE_REFS = """CREATE TABLE IF NOT EXISTS file_media_refs (
            payload_id TEXT PRIMARY KEY, digests TEXT NOT NULL, created_at INTEGER NOT NULL,
            sync_granted INTEGER NOT NULL DEFAULT 0)"""
        fun refs(db: RelayDbHelper, id: String): List<String> = db.readableDatabase.rawQuery(
            "SELECT digests FROM file_media_refs WHERE payload_id=?", arrayOf(id)).use {
            if(it.moveToFirst()) it.getString(0).split('\n').filter(String::isNotEmpty) else emptyList()
        }
        fun persistRefs(db: RelayDbHelper, id: String, digests: List<String>) {
            require(digests.size <= 8 && digests.distinct() == digests && digests.all { Regex("[0-9a-f]{64}").matches(it) })
            val sql = db.writableDatabase
            sql.execSQL("INSERT OR IGNORE INTO file_media_refs(payload_id,digests,created_at,sync_granted) VALUES(?,?,?,0)",
                arrayOf(id, digests.joinToString("\n"), System.currentTimeMillis()))
            check(refs(db,id) == digests) { "Media references changed across retry" }
        }
        fun markSyncGranted(db: RelayDbHelper, id: String) {
            db.writableDatabase.execSQL("UPDATE file_media_refs SET sync_granted=1 WHERE payload_id=?", arrayOf(id))
        }
    }
    private fun query(where: String = "1", args: Array<String> = emptyArray()): List<PhoneFileJob> = db.readableDatabase.rawQuery(
        "SELECT token,contact_id,payload_id,timestamp,wire,outgoing,recipients,transferred,confirmed,ready,queued,cancelled,error FROM phone_files WHERE $where", args,
    ).use { c -> buildList { while(c.moveToNext()) add(PhoneFileJob(c.getString(0),c.getLong(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5)!=0,
        c.getString(6).split('\n').filter(String::isNotEmpty),c.getInt(7),c.getInt(8),c.getInt(9)!=0,c.getInt(10)!=0,c.getInt(11)!=0,if(c.isNull(12)) null else c.getString(12))) } }
    override fun all() = query()
    override fun get(token: String) = query("token=?",arrayOf(token)).firstOrNull()
    fun forMessage(contact: Long, id: String) = query("contact_id=? AND payload_id=?",arrayOf(contact.toString(),id)).firstOrNull()
    private fun <T> transaction(block: () -> T): T {
        val sql = db.writableDatabase
        sql.beginTransaction()
        try { val result=block(); sql.setTransactionSuccessful(); return result } finally { sql.endTransaction() }
    }
    override fun insert(job: PhoneFileJob): Boolean = transaction {
        val contact = ContactRepository(db).getByIdSync(job.contact) ?: return@transaction false
        if(contact.isDeleted || contact.trustLevel == com.relay.app.data.model.ContactTrustLevel.BLOCKED) return@transaction false
        val author = if(job.outgoing) NostrIdentity.publicKeyHex(context) else contact.nostrPubkey ?: return@transaction false
        if(MessageDeletion.isDeleted(db,"direct:${job.contact}",author,job.id,job.timestamp) || !RetentionPolicy.admissible(job.timestamp,System.currentTimeMillis())) return@transaction false
        check(!db.readableDatabase.rawQuery("SELECT 1 FROM phone_files WHERE cancelled=0 AND ready=0 LIMIT 1",emptyArray<String>()).use {it.moveToFirst()}) { "Another file transfer reserves private storage" }
        val v=ContentValues().apply {
            put("token",job.token); put("contact_id",job.contact); put("payload_id",job.id); put("timestamp",job.timestamp); put("wire",job.wire)
            put("outgoing",if(job.outgoing) 1 else 0); put("recipients",job.recipients.joinToString("\n")); put("queued",if(job.queued) 1 else 0)
        }
        if(db.writableDatabase.insertWithOnConflict("phone_files",null,v,net.sqlcipher.database.SQLiteDatabase.CONFLICT_IGNORE)==-1L) return@transaction false
        val repo=MessageRepository(db)
        check(!repo.hasMsgIdSync(job.contact,job.id)) { "Existing message conflicts with file manifest" }
        check(repo.insertMessageSync(Message(contactId=job.contact,body=job.wire,type=MessageType.TEXT,isSent=job.outgoing,timestamp=job.timestamp,
            msgId=job.id,senderVerified=true,unread=!job.outgoing,deliveryState=if(job.outgoing && !job.queued) DeliveryState.QUEUED else DeliveryState.SENT)) != -1L)
        true
    }
    override fun update(job: PhoneFileJob) {
        val values=ContentValues().apply {
            put("transferred",job.transferred); put("confirmed",job.confirmed); put("ready",if(job.ready) 1 else 0)
            put("queued",if(job.queued) 1 else 0); put("cancelled",if(job.cancelled) 1 else 0); put("error",job.error)
        }
        db.writableDatabase.update("phone_files",values,"token=? AND cancelled=0",arrayOf(job.token))
        if(job.cancelled) db.writableDatabase.delete("outbox","payload_id=?",arrayOf(job.id))
    }
    override fun enqueue(job: PhoneFileJob) {
        // Crypto before the writer lock; no network. The source existence fence is rechecked in the transaction.
        val contact=ContactRepository(db).getByIdSync(job.contact)?.takeUnless { it.isDeleted } ?: error("Contact removed")
        val entry=Outgoing.prepareTo(requireNotNull(contact.nostrPubkey),contact.publicKey,contact.relayHints,contact.id,job.wire,job.id)
        transaction {
            check(get(job.token)?.cancelled == false && MessageRepository(db).hasMsgIdSync(job.contact,job.id)) { "File removed" }
            val manifest = requireNotNull(FileManifest.fromAuthenticatedPayload(RelayPayload(id=job.id,ts=job.timestamp,body=job.wire),FileBody.urlBasePredicate(MailboxMedia::isOurs)))
            persistRefs(db,job.id,manifest.body.parts.map { it.sha256Hex })
            check(Outgoing.enqueuePrepared(context,entry)!=null)
            com.relay.app.sync.SentSync.stageDirect(context,entry.recipientPubkeyHex,entry.payloadId,entry.createdAt,job.wire)
            check(db.readableDatabase.rawQuery("SELECT 1 FROM sent_sync_outbox WHERE payload_id=?",arrayOf(job.id)).use {it.moveToFirst()}) { "File SENT copy not staged" }
            update(job.copy(queued=true))
        }
    }
    override fun confirmationAllowed(job: PhoneFileJob): Boolean {
        if(job.recipients.isEmpty()) return true
        // Never infer success from an absent/deleted sync-outbox row: only publication marks this receipt.
        return db.readableDatabase.rawQuery("SELECT sync_granted FROM file_media_refs WHERE payload_id=?",arrayOf(job.id))
            .use { it.moveToFirst() && it.getInt(0)==1 }
    }
    fun cancelMissing() {
        val cutoff = RetentionPolicy.cutoff(System.currentTimeMillis())
        db.writableDatabase.execSQL("DELETE FROM phone_files WHERE timestamp<=?",arrayOf(cutoff))
        db.writableDatabase.execSQL("DELETE FROM file_media_refs WHERE created_at<=?",arrayOf(cutoff))
        // Retention, contact deletion and all message-delete entry points converge here; keep cancelled IDs as tombstones.
        db.writableDatabase.execSQL("UPDATE phone_files SET cancelled=1 WHERE NOT EXISTS(SELECT 1 FROM messages WHERE messages.contact_id=phone_files.contact_id AND messages.msg_id=phone_files.payload_id)")
        db.writableDatabase.execSQL("DELETE FROM outbox WHERE payload_id IN (SELECT payload_id FROM phone_files WHERE cancelled=1)")
    }
}
