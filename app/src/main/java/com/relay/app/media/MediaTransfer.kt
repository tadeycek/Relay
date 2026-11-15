package com.relay.app.media

import android.content.Context
import android.content.Intent
import android.util.Log

import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.model.Contact
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.MessageRepository
import com.relay.app.messaging.MessageDeletion
import com.relay.app.messaging.MessageNotifier
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.MailboxMedia
import com.relay.app.util.RelayPreferences
import java.io.File
import java.util.concurrent.Semaphore
import java.util.Base64
import org.json.JSONObject

/** Sending side: encrypt, upload, then queue the reference as a normal message. Blocking; call off the main thread. */
object MediaSender {

    private const val TAG = "MediaSender"
    class RecipientsChanged : IllegalStateException("Recipients changed; the upload will restart safely")

    sealed class Result {
        data class Sent(val msgId: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun send(context: Context, contact: Contact, file: File, mime: String, caption: String): Result {
        val ref = try { upload(context, file, mime, caption, listOfNotNull(contact.nostrPubkey)) } catch (e: Exception) { return Result.Failed(e.message ?: "Upload failed") }
        val msgId = Outgoing.enqueue(context, contact, MediaBody.format(ref))
            ?: return Result.Failed("Couldn't queue the message")
        return Result.Sent(msgId)
    }

    fun keepEncryptedCopy(context: Context, source: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val target = File(File(context.filesDir, "media").also { it.mkdirs() }, java.util.UUID.randomUUID().toString() + "." + source.extension)
        val size = source.length()
        require(size in 1..MediaBody.MAX_MEDIA_BYTES) { "File must be 3 GiB or smaller" }
        require(target.parentFile!!.usableSpace > size + MediaCrypto.PART_BYTES) { "Not enough free storage to queue this video" }
        progress(0,size)
        source.inputStream().use { RelayFileCrypto.writeEncrypted(context, target, it, size) { count -> progress(count,size) } }
        return target
    }

    private fun uploadFile(context: Context, source: File, suffix: String): File =
        File(File(context.filesDir, "media-uploads").also { it.mkdirs() }, source.name + suffix)

    fun clearUpload(context: Context, source: File) {
        listOf(".transfer", ".upload", ".upload.writing").forEach { uploadFile(context, source, it).delete() }
    }

    /** Ciphertext and its wrapped key survive process death, so retry never changes uploaded bytes. */
    fun uploadStaged(context: Context, source: File, mime: String, caption: String, recipients: List<String>,
                     progress: (String, Long, Long) -> Unit = { _, _, _ -> }): MediaRef {
        val plainSize = RelayFileCrypto.plainSize(context, source)
        require(plainSize in 1..MediaBody.MAX_MEDIA_BYTES)
        if (plainSize <= MediaBody.LEGACY_MAX_MEDIA_BYTES) {
            progress("Preparing", 0, plainSize)
            return uploadBytes(context, RelayFileCrypto.readDecrypted(context, source), mime, caption, recipients) { sent, total ->
                progress("Uploading", sent, total)
            }
        }
        check(MailboxConfig.available) { "Large videos require Relay's delivery server" }
        val ciphertext = uploadFile(context, source, ".transfer")
        val metadata = uploadFile(context, source, ".upload")
        val recipientSnapshot = org.json.JSONArray(recipients.distinct().sorted()).toString()
        val info = if (metadata.isFile && ciphertext.isFile) {
            JSONObject(String(RelayFileCrypto.readDecrypted(context, metadata), Charsets.UTF_8)).also {
                require(it.getLong("plainSize") == plainSize && it.getLong("cipherSize") == ciphertext.length()) { "Upload cache changed" }
                if (it.optString("recipients") != recipientSnapshot) {
                    clearUpload(context, source)
                    return uploadStaged(context, source, mime, caption, recipients, progress)
                }
            }
        } else {
            metadata.delete(); ciphertext.delete()
            require(ciphertext.parentFile!!.usableSpace > plainSize + MediaCrypto.PART_BYTES) { "Not enough free storage to prepare this upload" }
            val key = MediaCrypto.newKey()
            progress("Encrypting", 0, plainSize)
            try {
                RelayFileCrypto.openDecrypted(context, source).use { input ->
                    ciphertext.outputStream().use { output ->
                        val nonClosing = MediaCrypto.nonClosing(output)
                        val count = MediaCrypto.encryptStream(input, nonClosing, key, progress = { progress("Encrypting", it, plainSize) })
                        require(count == plainSize)
                        output.fd.sync()
                    }
                }
                JSONObject().put("plainSize", plainSize).put("cipherSize", ciphertext.length())
                    .put("hash", MediaCrypto.sha256Hex(ciphertext)).put("key", Base64.getEncoder().encodeToString(key))
                    .put("recipients", recipientSnapshot).also {
                        RelayFileCrypto.writeEncrypted(context, metadata, it.toString().toByteArray(Charsets.UTF_8))
                    }
            } catch (e: Exception) { ciphertext.delete(); throw e }
        }
        val hash = info.getString("hash")
        progress("Uploading", 0, ciphertext.length())
        val url = MailboxMedia.uploadChunked(context, ciphertext, hash, recipients) { sent, total -> progress("Uploading", sent, total) }
        return MediaRef(url, hash, info.getString("key"), mime, plainSize, caption, version = 2)
    }

    fun upload(context: Context, file: File, mime: String, caption: String, recipients: List<String>): MediaRef {
        require(file.length() in 1..MediaBody.LEGACY_MAX_MEDIA_BYTES) { "Use the resumable queue for large media" }
        return uploadBytes(context, file.readBytes(), mime, caption, recipients)
    }

    /**
     * Encrypts and uploads; only [recipients] (Nostr pubkeys) may download it from Relay's own server,
     * which deletes it once they all have it. Builds without a server use Blossom instead.
     */
    fun uploadBytes(context: Context, plain: ByteArray, mime: String, caption: String, recipients: List<String>,
                    progress: (Long, Long) -> Unit = { _, _ -> }): MediaRef {
        if (plain.isEmpty() || plain.size > MediaBody.LEGACY_MAX_MEDIA_BYTES) error("File is too large for the legacy upload format")

        val encrypted = MediaCrypto.encrypt(plain)
        val blobSha256 = MediaCrypto.sha256Hex(encrypted.blob)
        val url = if (MailboxConfig.available) {
            try {
                MailboxMedia.upload(context, encrypted.blob, blobSha256, recipients, progress)
            } catch (e: Exception) {
                Log.w(TAG, "upload to the delivery server failed: ${e.message}")
                error("Upload failed - check your connection and try again")
            }
        } else {
            val client = blossomClientFor(context) ?: error(TOR_UNAVAILABLE_MESSAGE)
            val servers = RelayPreferences(context).blossomServers
            val uploaded = client.upload(servers, encrypted.blob, blobSha256, "application/octet-stream")
            if (uploaded == null) {
                // The friendly message can't explain a server's own error text, but logcat can: pull it
                // when a report says uploads are failing.
                Log.w(TAG, "upload failed to all of $servers: ${client.lastUploadError}")
                error("Upload failed - check your connection and try again")
            }
            uploaded.url
        }

        return MediaRef(
            url = url,
            sha256Hex = blobSha256,
            keyBase64 = encrypted.keyBase64,
            mime = mime,
            size = plain.size.toLong(),
            caption = caption,
        )
    }
}

/** Receiving side: download, verify, decrypt, store encrypted at rest, then insert the message. */
object MediaReceiver {

    private var lastCleanup = 0L

    const val CREATE_INCOMING = """CREATE TABLE pending_media_receives (
        contact_id INTEGER NOT NULL, payload_id TEXT NOT NULL, ref TEXT NOT NULL,
        sent_at INTEGER NOT NULL, next_at INTEGER NOT NULL DEFAULT 0,
        attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(contact_id,payload_id))"""

    /** Persist the reference and placeholder before the mailbox envelope is acknowledged. */
    fun enqueue(context: Context, contact: Contact, payloadId: String, ref: MediaRef, sentAt: Long) {
        val db = MessagingDb.get(context)
        val repo = MessageRepository(db)
        synchronized(MessageDeletion) {
            if (repo.hasMsgIdSync(contact.id, payloadId)) return
            if (!contact.qrVerified) {
                store(context, repo, contact, payloadId, sentAt, MessageType.TEXT, "[Media from an unverified contact was not downloaded]", null)
                return
            }
            val sql = db.writableDatabase
            sql.beginTransaction()
            try {
                if (!store(context, repo, contact, payloadId, sentAt, if(ref.isVideo) MessageType.VIDEO else MessageType.IMAGE, ref.caption, null)) return
                sql.execSQL("INSERT OR IGNORE INTO pending_media_receives(contact_id,payload_id,ref,sent_at) VALUES(?,?,?,?)",
                    arrayOf(contact.id,payloadId,MediaBody.format(ref),sentAt))
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
        PendingAttachments.report(context, payloadId, "Waiting to download", 0, ref.size)
        MessageNotifier.notifyIncoming(context, contact, if(ref.isVideo) MessageType.VIDEO else MessageType.IMAGE, ref.caption)
    }

    /** Compatibility entry point; all receives now have a durable job. */
    fun receive(context: Context, contact: Contact, payloadId: String, ref: MediaRef, sentAt: Long) =
        enqueue(context, contact, payloadId, ref, sentAt)

    fun drain(context: Context) {
        val db = MessagingDb.get(context)
        if(System.currentTimeMillis()-lastCleanup > 60_000) {
            MediaDownload.cleanup(context)
            lastCleanup = System.currentTimeMillis()
        }
        data class Job(val contact: Long, val id: String, val body: String, val sentAt: Long, val attempts: Int)
        val jobs = db.readableDatabase.rawQuery("SELECT contact_id,payload_id,ref,sent_at,attempts FROM pending_media_receives WHERE next_at<=? ORDER BY sent_at LIMIT 8",
            arrayOf(System.currentTimeMillis().toString())).use { c -> buildList {
                while(c.moveToNext()) add(Job(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)))
            } }
        for (job in jobs) {
            if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
            val args = arrayOf(job.contact.toString(),job.id)
            val contact = com.relay.app.data.repository.ContactRepository(db).getByIdSync(job.contact)
            val ref = MediaBody.parse(job.body)
            val exists = db.readableDatabase.rawQuery("SELECT 1 FROM messages WHERE contact_id=? AND msg_id=?",args).use { it.moveToFirst() }
            if (contact == null || contact.isDeleted || contact.trustLevel == com.relay.app.data.model.ContactTrustLevel.BLOCKED || !contact.qrVerified || ref == null || !exists || com.relay.app.messaging.RetentionPolicy.expired(job.sentAt,System.currentTimeMillis())) {
                db.writableDatabase.delete("pending_media_receives","contact_id=? AND payload_id=?",args)
                PendingAttachments.clearProgress(context,job.id)
                ref?.let { File(File(context.filesDir,"media-downloads"),it.sha256Hex+".cipher").delete() }
                continue
            }
            var target: File? = null
            try {
                target = File(File(context.filesDir,"media").also { it.mkdirs() }, MediaCrypto.sha256Hex("${job.contact}:${job.id}".toByteArray()).take(32)+"."+extensionFor(ref.mime))
                MediaDownload.store(context,ref,target) { sent,total ->
                    check(!com.relay.app.calculator.CalculatorOnly.enabled(context)) { "Private session ended" }
                    PendingAttachments.report(context,job.id,"Downloading",sent,total)
                }
                synchronized(MessageDeletion) {
                    if (com.relay.app.calculator.CalculatorOnly.enabled(context) || MessageDeletion.isDeleted(db,"direct:${job.contact}",contact.nostrPubkey.orEmpty(),job.id,job.sentAt)) {
                        target.delete()
                    } else {
                        val changed = db.writableDatabase.update("messages",android.content.ContentValues().apply {
                            put("media_uri",target.absolutePath)
                        },"contact_id=? AND msg_id=?",args)
                        if(changed == 0) target.delete()
                    }
                    db.writableDatabase.delete("pending_media_receives","contact_id=? AND payload_id=?",args)
                }
                PendingAttachments.clearProgress(context,job.id)
                MailboxMedia.confirm(context,ref.url)
                context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id",job.contact))
            } catch (e: Exception) {
                target?.delete()
                db.writableDatabase.execSQL("UPDATE pending_media_receives SET attempts=attempts+1,next_at=? WHERE contact_id=? AND payload_id=?",
                    arrayOf(System.currentTimeMillis()+com.relay.app.transport.outbox.OutboxPolicy.nextDelayMs(job.attempts+1),job.contact,job.id))
                val current = PendingAttachments.progress.value[job.id]
                PendingAttachments.report(context,job.id,"Will retry",current?.transferred ?: 0,ref.size,e.message ?: "Download interrupted")
            }
        }
    }

    private fun store(
        context: Context,
        repo: MessageRepository,
        contact: Contact,
        payloadId: String,
        sentAt: Long,
        type: MessageType,
        body: String,
        mediaPath: String?,
    ): Boolean {
        synchronized(MessageDeletion) {
        if (com.relay.app.messaging.RetentionPolicy.expired(sentAt, System.currentTimeMillis()) || com.relay.app.calculator.CalculatorOnly.enabled(context) || MessageDeletion.isDeleted(MessagingDb.get(context), "direct:${contact.id}", contact.nostrPubkey.orEmpty(), payloadId, sentAt)) {
            mediaPath?.let { File(it).delete() }; return false
        }
        repo.insertMessageSync(
            Message(
                contactId = contact.id,
                body = body,
                type = type,
                isSent = false,
                timestamp = sentAt,
                mediaUri = mediaPath,
                msgId = payloadId,
                unread = true,
            )
        )
        }
        context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contact.id)
            setPackage(context.packageName)
        })
        return true
    }

    internal fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        else -> "bin"
    }
}
