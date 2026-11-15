package com.relay.app.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaBody
import android.media.MediaDataSource
import java.nio.ByteBuffer

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
    private val MAGIC = "RELAYF2\u0000".toByteArray(Charsets.US_ASCII)

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
        ByteArrayInputStream(plainBytes).use { writeEncrypted(context, file, it, plainBytes.size.toLong()) }
    }

    /** Bounded-memory, atomic local storage. A per-file streaming key is wrapped by Android Keystore. */
    fun writeEncrypted(context: Context, file: File, input: InputStream, plainSize: Long, progress: (Long) -> Unit = {}) {
        require(plainSize in 0..MediaBody.MAX_MEDIA_BYTES)
        val key = MediaCrypto.newKey()
        val wrapped = aead(context).encrypt(key, file.name.toByteArray(Charsets.UTF_8))
        val temporary = File(file.parentFile, file.name + ".writing")
        try {
            temporary.outputStream().use { raw ->
                val output = MediaCrypto.nonClosing(raw)
                val header = DataOutputStream(output)
                header.write(MAGIC)
                header.writeInt(wrapped.size)
                header.write(wrapped)
                header.writeLong(plainSize)
                val count = MediaCrypto.encryptStream(input, output, key, localContext(file, plainSize),progress)
                require(count == plainSize) { "Media size changed while reading" }
                raw.fd.sync()
            }
            check(temporary.renameTo(file)) { "Couldn't save encrypted media" }
        } finally { temporary.delete() }
    }

    private fun localContext(file: File, size: Long) =
        ("Relay local media v2|" + file.name + "|" + size).toByteArray(Charsets.UTF_8)

    /** Legacy one-shot files remain readable, but cannot allocate a large byte array. */
    fun openDecrypted(context: Context, file: File): InputStream {
        val input = file.inputStream().buffered()
        try {
            input.mark(MAGIC.size)
            val magic = ByteArray(MAGIC.size)
            val n = input.read(magic)
            if (n == MAGIC.size && magic.contentEquals(MAGIC)) {
                val header = DataInputStream(input)
                val keyLength = header.readInt()
                require(keyLength in 1..4096) { "Invalid encrypted media header" }
                val wrapped = ByteArray(keyLength).also { header.readFully(it) }
                val size = header.readLong()
                require(size in 0..MediaBody.MAX_MEDIA_BYTES) { "Invalid encrypted media size" }
                val key = aead(context).decrypt(wrapped, file.name.toByteArray(Charsets.UTF_8))
                return MediaCrypto.streaming(key).newDecryptingStream(input, localContext(file, size))
            }
            input.close()
            require(file.length() <= MediaBody.LEGACY_MAX_MEDIA_BYTES + 256) { "Unsupported legacy media size" }
            return ByteArrayInputStream(aead(context).decrypt(file.readBytes(), file.name.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) { input.close(); throw e }
    }

    fun plainSize(context: Context, file: File): Long = file.inputStream().buffered().use { input ->
        val header = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        header.readFully(magic)
        if (magic.contentEquals(MAGIC)) {
            val length = header.readInt()
            require(length in 1..4096)
            val wrapped = ByteArray(length).also { header.readFully(it) }
            require(aead(context).decrypt(wrapped, file.name.toByteArray(Charsets.UTF_8)).size == 32)
            header.readLong().also { require(it in 0..MediaBody.MAX_MEDIA_BYTES) }
        } else openDecrypted(context, file).use { it.available().toLong() }
    }

    /** Reads and decrypts a file previously written with [writeEncrypted]. Throws if [file] isn't one of ours. */
    fun readDecrypted(context: Context, file: File): ByteArray = openDecrypted(context, file).use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size().toLong() + n <= MediaBody.LEGACY_MAX_MEDIA_BYTES) { "Use streaming for large media" }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    fun copyDecrypted(context: Context, file: File, target: File) {
        try {
            openDecrypted(context, file).use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (e: Exception) { target.delete(); throw e }
    }

    private fun isPlainMedia(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(12)
        val n = input.read(head)
        n >= 8 && (String(head, 4, 4, Charsets.US_ASCII) == "ftyp" ||
            head.take(4) == listOf(0x1a.toByte(),0x45.toByte(),0xdf.toByte(),0xa3.toByte()) ||
            (head[0] == 0xff.toByte() && head[1] == 0xd8.toByte()) ||
            head.take(4) == listOf(0x89.toByte(),0x50.toByte(),0x4e.toByte(),0x47.toByte()) ||
            String(head,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a") ||
            (n == 12 && String(head,0,4,Charsets.US_ASCII) == "RIFF" && String(head,8,4,Charsets.US_ASCII) == "WEBP"))
    }

    /** Only recognized pre-encryption photos/videos may use the old plaintext fallback. */
    fun copyForView(context: Context, file: File, target: File) {
        if (isPlainMedia(file)) {
            require(file.length() <= MediaBody.MAX_MEDIA_BYTES)
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it,64 * 1024) } }
        } else copyDecrypted(context, file, target)
    }

    /** Video thumbnails seek through authenticated segments; composing a bubble never decrypts 3 GB. */
    fun mediaDataSource(context: Context, file: File): MediaDataSource {
        val header = DataInputStream(file.inputStream().buffered())
        val magic = ByteArray(MAGIC.size)
        val channel: java.nio.channels.SeekableByteChannel
        try {
            header.readFully(magic)
            channel = if (magic.contentEquals(MAGIC)) {
                val length = header.readInt()
                require(length in 1..4096)
                val wrapped = ByteArray(length).also { header.readFully(it) }
                val size = header.readLong()
                require(size in 0..MediaBody.MAX_MEDIA_BYTES)
                val key = aead(context).decrypt(wrapped,file.name.toByteArray(Charsets.UTF_8))
                val source = file.inputStream().channel
                try {
                    MediaCrypto.streaming(key).newSeekableDecryptingChannel(
                        OffsetByteChannel(source,(MAGIC.size+4+length+8).toLong()),localContext(file,size))
                } catch (e: Exception) { source.close(); throw e }
            } else if (isPlainMedia(file)) file.inputStream().channel
            else {
                val bytes = readDecrypted(context,file)
                return object : MediaDataSource() {
                    override fun getSize() = bytes.size.toLong()
                    override fun close() = Unit
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if(position < 0 || position >= bytes.size) return -1
                        val n = minOf(size.toLong(),bytes.size-position).toInt()
                        bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+n)
                        return n
                    }
                }
            }
        } finally { header.close() }
        return object : MediaDataSource() {
            override fun getSize() = channel.size()
            override fun close() = channel.close()
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if(position < 0 || position >= channel.size()) return -1
                channel.position(position)
                return channel.read(ByteBuffer.wrap(buffer,offset,size))
            }
        }
    }

    /**
     * Produces a plaintext copy of [encryptedFile] under a short-lived view cache, for handing to
     * Coil/MediaMetadataRetriever/etc. Falls back to copying the source bytes as-is if they turn
     * out not to be one of our encrypted files (e.g. media cached before this feature existed).
     */
    fun decryptedViewCopy(context: Context, encryptedFile: File): File {
        val viewDir = File(context.cacheDir, VIEW_CACHE_DIR).also { it.mkdirs() }
        val outFile = File(viewDir, encryptedFile.name)
        copyForView(context, encryptedFile, outFile)
        return outFile
    }

    /** Deletes view-cache files older than [maxAgeMs]. Call periodically (e.g. from the pin-expiry alarm). */
    fun purgeStaleViewCache(context: Context, maxAgeMs: Long = 10 * 60 * 1000L) {
        val viewDir = File(context.cacheDir, VIEW_CACHE_DIR)
        val cutoff = System.currentTimeMillis() - maxAgeMs
        viewDir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
    }
}
