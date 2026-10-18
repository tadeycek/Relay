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
import com.relay.app.util.RelayPreferences
import java.io.File
import java.util.concurrent.Semaphore

/** Sending side: encrypt, upload, then queue the reference as a normal message. Blocking; call off the main thread. */
object MediaSender {

    private const val TAG = "MediaSender"

    sealed class Result {
        data class Sent(val msgId: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun send(context: Context, contact: Contact, file: File, mime: String, caption: String): Result {
        val ref = try { upload(context, file, mime, caption) } catch (e: Exception) { return Result.Failed(e.message ?: "Upload failed") }
        val msgId = Outgoing.enqueue(context, contact, MediaBody.format(ref))
            ?: return Result.Failed("Couldn't queue the message")
        return Result.Sent(msgId)
    }

    fun keepEncryptedCopy(context: Context, source: File): File {
        val target = File(File(context.filesDir, "media").also { it.mkdirs() }, java.util.UUID.randomUUID().toString() + "." + source.extension)
        RelayFileCrypto.writeEncrypted(context, target, source.readBytes())
        return target
    }

    fun upload(context: Context, file: File, mime: String, caption: String): MediaRef {
        return uploadBytes(context, file.readBytes(), mime, caption)
    }

    fun uploadBytes(context: Context, plain: ByteArray, mime: String, caption: String): MediaRef {
        if (plain.isEmpty() || plain.size > MediaBody.MAX_MEDIA_BYTES) error("File is too large")

        val client = blossomClientFor(context) ?: error(TOR_UNAVAILABLE_MESSAGE)
        val encrypted = MediaCrypto.encrypt(plain)
        val blobSha256 = MediaCrypto.sha256Hex(encrypted.blob)
        val servers = RelayPreferences(context).blossomServers
        val uploaded = client.upload(servers, encrypted.blob, blobSha256, "application/octet-stream")
        if (uploaded == null) {
            // The friendly message can't explain a server's own error text, but logcat can: pull it
            // when a report says uploads are failing.
            Log.w(TAG, "upload failed to all of $servers: ${client.lastUploadError}")
            error("Upload failed - check your connection and try again")
        }

        return MediaRef(
            url = uploaded.url,
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

    private const val TAG = "MediaReceiver"
    private const val BLOB_OVERHEAD = 12L + 16L // iv + gcm tag

    /** Bounds memory and bandwidth if a contact sends many references at once. */
    private val slots = Semaphore(2)

    fun receive(context: Context, contact: Contact, payloadId: String, ref: MediaRef, sentAt: Long) {
        val repo = MessageRepository(MessagingDb.get(context))
        // A retried payload whose media was already stored: the file name derives from the payload id,
        // so downloading again would overwrite the stored file and add a second row for it.
        if (repo.hasMsgIdSync(contact.id, payloadId)) return
        val alertToken = com.relay.app.messaging.ConversationNotifications.token(context, contact.id)

        // Fetching a URL a sender chose reveals our IP to that host, so only do it for contacts whose
        // QR code we scanned in person. Anyone else (a stranger who knows our key can announce any
        // name and key) gets a placeholder instead.
        if (!contact.qrVerified) {
            store(context, repo, contact, payloadId, sentAt, MessageType.TEXT, "[Media from an unverified contact was not downloaded]", null)
            return
        }

        val client = blossomClientFor(context)
        if (client == null) {
            store(context, repo, contact, payloadId, sentAt, MessageType.TEXT, "[Media not downloaded: Tor is turned on but isn't running]", null)
            return
        }

        slots.acquireUninterruptibly()
        try {
            // Allow enough room for legacy JPEG containers while bounding raw ciphertext downloads.
            // Both formats are hash-verified before any decryption.
            val cipherSize = ref.size + BLOB_OVERHEAD
            val maxBytes = if (cipherSize in 0..Int.MAX_VALUE) MediaContainer.wrappedSize(cipherSize.toInt()).toLong() else Long.MAX_VALUE
            val wrapped = client.download(ref.url, ref.sha256Hex, maxBytes)
            // Accept the old container for existing conversations; new uploads are raw ciphertext.
            val unwrapped = wrapped?.let { MediaContainer.unwrap(it) ?: it }
            val plain = unwrapped?.let { MediaCrypto.decrypt(it, ref.keyBase64) }
            if (plain == null || plain.size.toLong() != ref.size) {
                store(context, repo, contact, payloadId, sentAt, MessageType.TEXT, "[Media couldn't be downloaded or verified]", null)
                return
            }
            val dir = File(context.filesDir, "media").also { it.mkdirs() }
            // Never derive a path from the sender-controlled payload id (could contain "../").
            val name = MediaCrypto.sha256Hex(payloadId.toByteArray()).take(32) + "." + extensionFor(ref.mime)
            val file = File(dir, name)
            RelayFileCrypto.writeEncrypted(context, file, plain)
            val type = if (ref.isVideo) MessageType.VIDEO else MessageType.IMAGE
            synchronized(MessageDeletion) {
                if (store(context, repo, contact, payloadId, sentAt, type, ref.caption, file.absolutePath))
                    MessageNotifier.notifyIncoming(context, contact, type, ref.caption, alertToken)
            }
        } catch (e: Exception) {
            Log.w(TAG, "media receive failed: ${e.message}")
            store(context, repo, contact, payloadId, sentAt, MessageType.TEXT, "[Media couldn't be downloaded or verified]", null)
        } finally {
            slots.release()
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
        else -> "bin"
    }
}
