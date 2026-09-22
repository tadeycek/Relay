package com.relay.app.p2p

import android.content.Context
import android.util.Log
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.model.Contact
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaReceiver
import com.relay.app.messaging.MessageNotifier
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.messaging.Outgoing
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Sending side of a direct phone-to-phone transfer: no media server touches any of this. */
object P2pSender {

    private const val TAG = "P2pSender"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val TRANSFER_TIMEOUT_MS = 60_000

    sealed class Result {
        data class Sent(val msgId: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * [candidates] are addresses the recipient offered in their Pong (see [PresenceState.Online]).
     * [nonce] is that same presence check's nonce — it authenticates both the offer message and the
     * socket connection as belonging to this one check, without exposing the decryption key on the
     * socket itself (see [P2pOfferMessages], [P2pWire]).
     */
    fun send(context: Context, contact: Contact, candidates: List<String>, nonce: String, plain: ByteArray, mime: String, caption: String): Result {
        val encrypted = MediaCrypto.encrypt(plain)
        val offerBody = P2pOfferMessages.format(
            P2pOfferMessages.Offer(nonce, encrypted.sha256Hex, encrypted.keyBase64, mime, encrypted.blob.size.toLong(), caption)
        )
        val msgId = Outgoing.enqueue(context, contact, offerBody) ?: return Result.Failed("Couldn't reach them")

        for (candidate in candidates) {
            val address = parseAddress(candidate) ?: continue
            try {
                Socket().use { socket ->
                    socket.connect(address, CONNECT_TIMEOUT_MS)
                    socket.soTimeout = TRANSFER_TIMEOUT_MS
                    P2pWire.writeHeader(socket.getOutputStream(), P2pWire.Header(nonce))
                    socket.getOutputStream().write(encrypted.blob)
                    socket.getOutputStream().flush()
                }
                return Result.Sent(msgId)
            } catch (e: Exception) {
                Log.i(TAG, "connect to $candidate failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return Result.Failed("Couldn't connect directly — this doesn't always work across different networks")
    }

    private fun parseAddress(candidate: String): InetSocketAddress? {
        if (!PresenceMessages.isValidCandidate(candidate)) return null
        val portIdx = candidate.lastIndexOf(':')
        val host = candidate.substring(0, portIdx).removeSurrounding("[", "]")
        val port = candidate.substring(portIdx + 1).toIntOrNull() ?: return null
        return InetSocketAddress(host, port)
    }
}

/**
 * Receiving side: listens on [PresenceCoordinator.P2P_PORT] for direct transfers. Runs as long as
 * [com.relay.app.messaging.MessagingRuntime] does — the same lifetime as the transport's own incoming
 * loop it sits next to (see `MessagingRuntime.ensureStarted`) — not only while a transfer is expected,
 * since a listening TCP port that only sometimes exists is more failure-prone for NAT/router mapping
 * than one that's always there; an unrecognised connection (no matching offer) is simply dropped.
 */
object P2pListener {

    private const val TAG = "P2pListener"
    private const val MAX_CIPHERTEXT_BYTES = 50L * 1024 * 1024 + 64

    @Volatile private var started = false

    @Synchronized
    fun ensureListening(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        MessagingRuntime.launchIo { listenLoop(appContext) }
    }

    private suspend fun listenLoop(context: Context) {
        val server = try {
            java.net.ServerSocket(PresenceCoordinator.P2P_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't bind the P2P listening port: ${e.message}")
            started = false
            return
        }
        server.use {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                MessagingRuntime.launchIo { handleConnection(context, socket) }
            }
        }
    }

    private fun handleConnection(context: Context, socket: Socket) {
        socket.use {
            socket.soTimeout = 30_000
            val header = P2pWire.readHeader(socket.getInputStream())
            if (header == null) {
                Log.i(TAG, "dropped a connection with no valid header")
                return
            }
            val (contactId, offer) = PresenceCoordinator.consumeOffer(header.nonce) ?: run {
                Log.i(TAG, "dropped a connection with no matching offer")
                return
            }
            val contact = ContactRepository(MessagingDb.get(context)).getByIdSync(contactId)
            if (contact == null || contact.isDeleted) return

            val blob = P2pWire.readBlob(socket.getInputStream(), offer.size, MAX_CIPHERTEXT_BYTES)
            if (blob == null || MediaCrypto.sha256Hex(blob) != offer.sha256Hex) {
                Log.w(TAG, "P2P transfer failed hash/size check")
                return
            }
            val plain = MediaCrypto.decrypt(blob, offer.keyBase64)
            if (plain == null) {
                Log.w(TAG, "P2P transfer failed to decrypt")
                return
            }

            val dir = File(context.filesDir, "media").also { it.mkdirs() }
            val name = MediaCrypto.sha256Hex(offer.nonce.toByteArray()).take(32) + "." + MediaReceiver.extensionFor(offer.mime)
            val file = File(dir, name)
            RelayFileCrypto.writeEncrypted(context, file, plain)

            val repo = MessageRepository(MessagingDb.get(context))
            val type = if (offer.mime.startsWith("video/")) MessageType.VIDEO else MessageType.IMAGE
            repo.insertMessageSync(
                Message(
                    contactId = contact.id,
                    body = offer.caption,
                    type = type,
                    isSent = false,
                    timestamp = System.currentTimeMillis(),
                    mediaUri = file.absolutePath,
                    deliveryState = DeliveryState.NONE,
                    unread = true,
                )
            )
            context.sendBroadcast(android.content.Intent("com.relay.app.NEW_MESSAGE").apply {
                putExtra("contact_id", contact.id)
                setPackage(context.packageName)
            })
            MessageNotifier.notifyIncoming(context, contact, type)
        }
    }
}
