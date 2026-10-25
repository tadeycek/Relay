package com.relay.app.desktop

import com.relay.app.media.MediaBody
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaRef
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.mailbox.MailboxMedia
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Diagnostics only (`--seed-demo-media`, requires `--show-chat-ui` and explicit dirs): an in-memory
 * "server" so a screenshot can show a received photo and a received video through the real receive
 * path (download, decrypt, classify, durable save, confirm). It never touches a network.
 */
internal class LoopbackMediaTransport : DesktopMediaTransport {
    override val urlBase = "https://demo.invalid/v1/media/"
    val blobs = HashMap<String, ByteArray>()
    override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) { blobs[hash] = file.readBytes() }
    override fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) {
        val bytes = blobs[hash] ?: throw MailboxMedia.HttpFailure(404, "media_not_found", false)
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        file.writeBytes(bytes)
    }
    override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation) = true
}

internal object DesktopMediaDemo {
    /** Receives [photo] (and a tiny synthetic MP4 header) from [peer] as if a phone had sent them. */
    suspend fun seed(engine: DesktopEngine, loopback: LoopbackMediaTransport, peer: String, photo: Path) {
        engine.store.ensureConversation(peer, "Demo contact")
        val items = listOf(Files.readAllBytes(photo) to "image/jpeg", mp4Stub() to "video/mp4")
        for ((plain, mime) in items) {
            val enc = MediaCrypto.encrypt(plain)
            loopback.blobs[enc.sha256Hex] = enc.blob
            val ref = MediaRef(loopback.urlBase + enc.sha256Hex, enc.sha256Hex, enc.keyBase64, mime, plain.size.toLong(), if (mime.startsWith("video")) "clip from my phone" else "sunset")
            val msg = DesktopMessage(PayloadCodec.newId(), peer, peer, MediaBody.format(ref), System.currentTimeMillis(), false)
            if (engine.media?.accept(msg) == true) engine.retryMedia(msg.id)
        }
    }
    private fun mp4Stub(): ByteArray = java.nio.ByteBuffer.allocate(2048).putInt(24).put("ftyp".toByteArray()).put("isom".toByteArray()).putInt(0).put("isomiso2".toByteArray()).array()
}
