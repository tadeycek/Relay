package com.relay.app.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/**
 * At-rest encryption for cached media files (received/compressed MMS
 * attachments living under the app cache dir). Independent of the E2E
 * transport encryption in [RelayCrypto] — this protects files sitting on
 * disk against root/ADB/physical-access extraction, regardless of whether
 * the sender/recipient supports E2E.
 *
 * Ciphertext files aren't directly renderable by Coil/MediaMetadataRetriever,
 * so callers that need to *display* a file should go through
 * [decryptedViewCopy], which writes a plaintext copy into a short-lived view
 * cache rather than decrypting the canonical copy in place.
 */
object RelayFileCrypto {

    private const val KEYSET_NAME = "relay_media_keyset"
    private const val PREF_FILE_NAME = "relay_media_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://relay_media_master_key"
    private const val VIEW_CACHE_DIR = "mms_view"

    @Volatile private var handle: KeysetHandle? = null
    @Volatile private var configRegistered = false

    private fun aead(context: Context): Aead {
        cachedAead()?.let { return it }
        synchronized(this) {
            cachedAead()?.let { return it }
            if (!configRegistered) {
                TinkConfig.register()
                configRegistered = true
            }
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            handle = manager.keysetHandle
        }
        return handle!!.getPrimitive(Aead::class.java)
    }

    private fun cachedAead(): Aead? = handle?.getPrimitive(Aead::class.java)

    /** Encrypts [plainBytes] and writes the ciphertext to [file], replacing any existing content. */
    fun writeEncrypted(context: Context, file: File, plainBytes: ByteArray) {
        val ciphertext = aead(context).encrypt(plainBytes, file.name.toByteArray(Charsets.UTF_8))
        file.writeBytes(ciphertext)
    }

    /** Reads and decrypts a file previously written with [writeEncrypted]. Throws if [file] isn't one of ours. */
    fun readDecrypted(context: Context, file: File): ByteArray =
        aead(context).decrypt(file.readBytes(), file.name.toByteArray(Charsets.UTF_8))

    /**
     * Produces a plaintext copy of [encryptedFile] under a short-lived view cache, for handing to
     * Coil/MediaMetadataRetriever/etc. Falls back to copying the source bytes as-is if they turn
     * out not to be one of our encrypted files (e.g. media cached before this feature existed).
     */
    fun decryptedViewCopy(context: Context, encryptedFile: File): File {
        val viewDir = File(context.cacheDir, VIEW_CACHE_DIR).also { it.mkdirs() }
        val outFile = File(viewDir, encryptedFile.name)
        val plainBytes = try {
            readDecrypted(context, encryptedFile)
        } catch (e: Exception) {
            encryptedFile.readBytes()
        }
        outFile.writeBytes(plainBytes)
        return outFile
    }

    /** Deletes view-cache files older than [maxAgeMs]. Call periodically (e.g. from the pin-expiry alarm). */
    fun purgeStaleViewCache(context: Context, maxAgeMs: Long = 10 * 60 * 1000L) {
        val viewDir = File(context.cacheDir, VIEW_CACHE_DIR)
        val cutoff = System.currentTimeMillis() - maxAgeMs
        viewDir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
    }
}
