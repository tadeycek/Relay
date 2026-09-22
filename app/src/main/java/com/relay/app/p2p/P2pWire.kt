package com.relay.app.p2p

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The framing for a direct phone-to-phone media transfer socket: a tiny header, then the raw ciphertext
 * to EOF. The header carries only a nonce — proof this connection corresponds to a presence check the
 * receiver actually answered — never the decryption key or any other metadata a snooper on the socket
 * could use. All of that (hash, size, mime, caption, key) travels separately over the normal end-to-end
 * encrypted message channel; see [P2pOfferMessages]. No media server is involved, so unlike
 * [com.relay.app.media.MediaBody] there is no URL and no need to disguise the blob's content type (see
 * [com.relay.app.media.MediaContainer]) — the ciphertext is sent exactly as
 * [com.relay.app.media.MediaCrypto.encrypt] produced it.
 */
object P2pWire {

    /** Guards against a malicious or corrupt peer claiming an absurd header size before it's read. */
    const val MAX_HEADER_BYTES = 1 * 1024

    data class Header(val nonce: String)

    fun writeHeader(out: OutputStream, header: Header) {
        val json = JSONObject().apply { put("nonce", header.nonce) }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_HEADER_BYTES) { "header too large" }
        val data = DataOutputStream(out)
        data.writeInt(json.size)
        data.write(json)
        data.flush()
    }

    /** Null for a malformed or oversized header, or a stream that ends before one is fully read. */
    fun readHeader(input: InputStream): Header? = try {
        val data = DataInputStream(input)
        val len = data.readInt()
        if (len <= 0 || len > MAX_HEADER_BYTES) null
        else {
            val bytes = ByteArray(len)
            data.readFully(bytes)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val nonce = json.getString("nonce")
            if (!PresenceMessages.isValidNonce(nonce)) null else Header(nonce)
        }
    } catch (e: java.io.EOFException) {
        null
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Reads exactly [size] bytes, or null if the stream ends early or [size] exceeds [maxBytes]. */
    fun readBlob(input: InputStream, size: Long, maxBytes: Long): ByteArray? {
        if (size <= 0 || size > maxBytes) return null
        val out = ByteArrayOutputStream(size.toInt().coerceAtMost(1 shl 20))
        val buf = ByteArray(16 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) return null
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }
}
