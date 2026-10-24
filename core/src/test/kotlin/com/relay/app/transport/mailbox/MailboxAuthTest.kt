package com.relay.app.transport.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** WP03: NIP-98 header assembly with an injected signer (no Nostr SDK in :core). */
class MailboxAuthTest {

    private class Recording : NostrSigner {
        var kind = -1
        var content: String? = null
        var tags: List<List<String>> = emptyList()
        override fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String {
            this.kind = kind; this.content = content; this.tags = tags
            return """{"kind":$kind,"content":"","sig":"s\u00e9"}"""
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun headerCarriesKind27235AndTheBase64OfTheSignedEventJson() {
        val s = Recording()
        val h = MailboxAuth.header(s, "GET", "https://m.example/v1/ws")
        assertEquals(27235, s.kind)
        assertEquals("", s.content)
        assertTrue(h.startsWith("Nostr "))
        val json = String(Base64.getDecoder().decode(h.removePrefix("Nostr ")), Charsets.UTF_8)
        assertEquals("""{"kind":27235,"content":"","sig":"s\u00e9"}""", json)
        assertEquals(false, h.contains('\n'))
    }

    @Test fun tagsBindUrlMethodAndPayloadHashInOrderWithAFreshNonce() {
        val body = "payload bytes".toByteArray()
        val s = Recording()
        MailboxAuth.header(s, "PUT", "https://m.example/v1/media/abc", body)
        assertEquals(listOf("u", "https://m.example/v1/media/abc"), s.tags[0])
        assertEquals(listOf("method", "PUT"), s.tags[1])
        assertEquals(listOf("payload", sha(body)), s.tags[2])
        assertEquals("nonce", s.tags[3][0])
        assertTrue(Regex("[0-9a-f]{32}").matches(s.tags[3][1]))
        assertEquals(4, s.tags.size)
        val first = s.tags[3][1]
        MailboxAuth.header(s, "PUT", "https://m.example/v1/media/abc", body)
        assertNotEquals(first, s.tags[3][1])
    }

    @Test fun emptyOrAbsentBodyHasNoPayloadTag() {
        val s = Recording()
        MailboxAuth.header(s, "GET", "u")
        assertEquals(listOf("u", "method", "nonce"), s.tags.map { it[0] })
        MailboxAuth.header(s, "POST", "u", ByteArray(0))
        assertEquals(listOf("u", "method", "nonce"), s.tags.map { it[0] })
    }

    @Test fun headerIsJsonCompatibleWhenTheSignerReturnsRealEventShape() {
        val signer = object : NostrSigner {
            override fun signEventJson(kind: Int, content: String, tags: List<List<String>>) =
                JSONObject().put("kind", kind).put("content", content).put("tags", org.json.JSONArray(tags)).toString()
        }
        val h = MailboxAuth.header(signer, "DELETE", "https://x/y")
        val o = JSONObject(String(Base64.getDecoder().decode(h.removePrefix("Nostr "))))
        assertEquals(27235, o.getInt("kind"))
        assertEquals(3, o.getJSONArray("tags").length())
    }
}
