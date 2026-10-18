package com.relay.app.transport.mailbox

import android.util.Base64
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * NIP-98 `Authorization` header for one mailbox request, signed with this install's Nostr identity:
 * a kind-27235 event bound to the exact URL, the method and the body's SHA-256. The random nonce keeps
 * two identical requests in the same second from sharing an event id, which the server would refuse
 * as a replay. The server accepts it for 60 s, so the phone's clock has to be roughly right.
 */
internal object MailboxAuth {

    private const val KIND: UShort = 27235u
    private val random = SecureRandom()

    fun header(keys: Keys, method: String, url: String, body: ByteArray? = null): String {
        val tags = buildList {
            add(listOf("u", url))
            add(listOf("method", method))
            if (body != null && body.isNotEmpty()) add(listOf("payload", hex(MessageDigest.getInstance("SHA-256").digest(body))))
            add(listOf("nonce", hex(ByteArray(16).also(random::nextBytes))))
        }.map { Tag.parse(it) }
        val event = EventBuilder(Kind(KIND), "").tags(tags).signWithKeys(keys)
        return "Nostr " + Base64.encodeToString(event.asJson().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
