package com.relay.app.media

import android.content.Context
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.transport.mailbox.MailboxMedia
import java.io.File

/** One bounded download at a time across direct/group chats; partial ciphertext survives retries. */
object MediaDownload {
    /** Shares the transfer lock so a deletion cannot remove a file that is currently being written. */
    @Synchronized
    fun cleanup(context: Context) {
        val db = com.relay.app.messaging.MessagingDb.get(context)
        val hashes = db.readableDatabase.rawQuery(
            "SELECT ref FROM pending_media_receives UNION ALL SELECT media_ref FROM group_messages WHERE media_ref IS NOT NULL",null).use { c ->
            buildSet { while(c.moveToNext()) MediaBody.parse(c.getString(0))?.let { add(it.sha256Hex) } }
        }
        File(context.filesDir,"media-downloads").listFiles()?.forEach { file ->
            if(file.name.endsWith(".cipher") && file.name.removeSuffix(".cipher") !in hashes) file.delete()
        }
    }

    @Synchronized
    fun store(context: Context, ref: MediaRef, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(ref.size in 1..MediaBody.MAX_MEDIA_BYTES)
        if (ref.version == 2) {
            require(MailboxMedia.isChunked(ref.url)) { "Unsupported media server" }
            val cipher = File(File(context.filesDir, "media-downloads").also { it.mkdirs() }, ref.sha256Hex + ".cipher")
            val cipherSize = MediaCrypto.cipherSize(ref.size)
            val prefix = if(cipher.isFile && cipher.length() <= cipherSize) cipher.length()/MediaCrypto.PART_BYTES*MediaCrypto.PART_BYTES else 0L
            val required = cipherSize-prefix + cipherSize + MediaCrypto.PART_BYTES
            require(target.parentFile!!.usableSpace > required) { "Not enough free storage: this video needs room for its download and encrypted copy" }
            MailboxMedia.downloadChunked(context, ref.url, ref.sha256Hex, cipher, progress)
            try {
                cipher.inputStream().use { input ->
                    MediaCrypto.decryptingStream(input, ref.keyBase64).use { plain ->
                        RelayFileCrypto.writeEncrypted(context, target, plain, ref.size)
                    }
                }
            } catch (e: Exception) { target.delete(); throw e }
            cipher.delete()
        } else {
            require(ref.size <= MediaBody.LEGACY_MAX_MEDIA_BYTES)
            require(target.parentFile!!.usableSpace > ref.size + MediaCrypto.PART_BYTES) { "Not enough free storage for this media" }
            val fetch = mediaFetcher(context, ref.url) ?: error("Media server is unavailable")
            val max = MediaContainer.wrappedSize((ref.size + 28).toInt()).toLong()
            val bytes = fetch(ref.url, ref.sha256Hex, max) ?: error("Media download interrupted")
            val plain = MediaCrypto.decrypt(MediaContainer.unwrap(bytes) ?: bytes, ref.keyBase64) ?: error("Invalid encrypted media")
            require(plain.size.toLong() == ref.size)
            RelayFileCrypto.writeEncrypted(context, target, plain)
            progress(ref.size, ref.size)
        }
    }
}
