package com.relay.app.transport.mailbox

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * NIP-98 `Authorization` header for one mailbox request, signed with this install's Nostr identity:
 * a kind-27235 event bound to the exact URL, the method and the body's SHA-256. The random nonce keeps
 * two identical requests in the same second from sharing an event id, which the server would refuse
 * as a replay. The server accepts it for 60 s, so the phone's clock has to be roughly right.
 */
object MailboxAuth {

    private const val KIND = 27235
    private val random = SecureRandom()

    fun header(signer: NostrSigner, method: String, url: String, body: ByteArray? = null): String {
        return headerWithDigest(signer, method, url,
            body?.takeIf { it.isNotEmpty() }?.let { hex(MessageDigest.getInstance("SHA-256").digest(it)) })
    }

    /** Signs an already computed ciphertext digest; never reads or retains the request body. */
    fun headerWithDigest(signer: NostrSigner, method: String, url: String, sha256Hex: String?): String {
        require(sha256Hex == null || Regex("^[0-9a-f]{64}$").matches(sha256Hex)) { "Invalid SHA-256 digest" }
        val tags = buildList {
            add(listOf("u", url))
            add(listOf("method", method))
            if (sha256Hex != null) add(listOf("payload", sha256Hex))
            add(listOf("nonce", hex(ByteArray(16).also(random::nextBytes))))
        }
        val eventJson = signer.signEventJson(KIND, "", tags)
        return "Nostr " + Base64.getEncoder().encodeToString(eventJson.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
