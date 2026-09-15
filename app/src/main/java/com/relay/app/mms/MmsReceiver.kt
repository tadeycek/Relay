package com.relay.app.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.relay.app.crypto.RelayCrypto
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import java.io.File

class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // WAP_PUSH_DELIVER only ever arrives when Relay is the default SMS app (see
        // AndroidManifest.xml); WAP_PUSH_RECEIVED still arrives otherwise. Handle either the same way.
        if (intent.action != "android.provider.Telephony.WAP_PUSH_RECEIVED" &&
            intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER"
        ) return
        val mime = intent.type ?: return
        if (!mime.equals("application/vnd.wap.mms-message", ignoreCase = true)) return

        // Allow system to store the MMS before we read it
        Handler(Looper.getMainLooper()).postDelayed({
            readNewInboxMms(context)
        }, 3_000L)
    }

    companion object {

        fun readNewInboxMms(context: Context) {
            try {
                val db = RelayDbHelper(context)
                val contactRepo = ContactRepository(db)
                val messageRepo = MessageRepository(db)

                val cursor = context.contentResolver.query(
                    Uri.parse("content://mms/inbox"),
                    arrayOf("_id", "date"),
                    null, null,
                    "date DESC LIMIT 10",
                ) ?: return

                cursor.use {
                    while (it.moveToNext()) {
                        val mmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                        val phone = getAddressForMms(context, mmsId) ?: continue
                        val contact = contactRepo.findByPhoneSync(phone) ?: continue

                        val (partUri, mimeType) = getMediaPart(context, mmsId) ?: continue

                        // Copy to cache so we own the file
                        val msg = when (val copyResult = copyPartToCache(context, partUri, mimeType)) {
                            is CopyResult.Success -> {
                                // Skip if already stored
                                if (messageRepo.hasMediaUri(copyResult.path)) continue
                                val type = if (mimeType.startsWith("video")) MessageType.VIDEO else MessageType.IMAGE
                                Message(
                                    contactId = contact.id,
                                    body = "",
                                    type = type,
                                    isSent = false,
                                    mediaUri = copyResult.path,
                                )
                            }
                            CopyResult.DecryptionFailed -> Message(
                                contactId = contact.id,
                                body = "[Unable to decrypt media]",
                                type = MessageType.TEXT,
                                isSent = false,
                            )
                            CopyResult.Failed -> continue
                        }
                        messageRepo.insertMessageSync(msg)

                        context.sendBroadcast(
                            Intent("com.relay.app.NEW_MESSAGE").apply {
                                putExtra("contact_id", contact.id)
                                setPackage(context.packageName)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MmsReceiver", "Error reading MMS inbox", e)
            }
        }

        private fun getAddressForMms(context: Context, mmsId: Long): String? {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            return context.contentResolver.query(
                uri, arrayOf("address"), "type=137", null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }

        private fun getMediaPart(context: Context, mmsId: Long): Pair<String, String>? {
            return context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct"),
                "mid=?", arrayOf(mmsId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getLong(c.getColumnIndexOrThrow("_id"))
                    val ct = c.getString(c.getColumnIndexOrThrow("ct")) ?: continue
                    if (ct.startsWith("image/") || ct.startsWith("video/")) {
                        return@use Pair("content://mms/part/$partId", ct)
                    }
                }
                null
            }
        }

        /** Result of [copyPartToCache] — distinguishes "this part was encrypted but we couldn't
         *  decrypt it" from every other failure, so the caller can surface that distinctly
         *  instead of silently dropping the message (mirrors SmsReceiver's text-decrypt path). */
        private sealed class CopyResult {
            data class Success(val path: String) : CopyResult()
            object DecryptionFailed : CopyResult()
            object Failed : CopyResult()
        }

        private fun copyPartToCache(context: Context, partUri: String, mimeType: String): CopyResult = try {
            val ext = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("png")  -> "png"
                mimeType.contains("gif")  -> "gif"
                mimeType.contains("video") -> "mp4"
                else -> "bin"
            }
            val rawBytes = context.contentResolver.openInputStream(Uri.parse(partUri))?.use { it.readBytes() }
                ?: return CopyResult.Failed
            val plainBytes = decryptIfEncrypted(context, rawBytes) ?: return CopyResult.DecryptionFailed

            val file = File(context.cacheDir, "mms/recv_${System.currentTimeMillis()}.$ext")
                .also { it.parentFile?.mkdirs() }
            // At-rest encryption: the file on disk is ciphertext even though the sender's E2E
            // encryption (if any) has already been unwrapped above. Display code must go through
            // RelayFileCrypto.decryptedViewCopy() to render this.
            RelayFileCrypto.writeEncrypted(context, file, plainBytes)
            CopyResult.Success(file.absolutePath)
        } catch (e: Exception) {
            Log.e("MmsReceiver", "Failed to copy MMS part $partUri", e)
            CopyResult.Failed
        }

        /**
         * Strips and decrypts the E2E envelope if [bytes] carry [RelayCrypto.MMS_ENC_MAGIC];
         * returns [bytes] unchanged if there's no magic prefix (never encrypted). Returns null
         * only when the magic prefix IS present but decryption fails — that case must be
         * distinguishable from "not encrypted," since silently falling back to the raw ciphertext
         * (the old behavior) stored garbage on disk and displayed it as if it were valid media,
         * with zero indication anything was wrong.
         */
        private fun decryptIfEncrypted(context: Context, bytes: ByteArray): ByteArray? {
            val magic = RelayCrypto.MMS_ENC_MAGIC
            if (bytes.size <= magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
                return bytes
            }
            val ciphertext = bytes.copyOfRange(magic.size, bytes.size)
            return RelayCrypto.decryptMine(context, ciphertext)
        }
    }
}
