package com.relay.app.messaging

import com.relay.app.crypto.KeyMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** WP03: the pure part of InnerEnvelope, with the key operations injected. */
class InnerEnvelopeTest {

    /** "Encrypts" by reversing; records what it was asked. */
    private class FakeKeys(private val encryptResult: ((ByteArray) -> ByteArray?)? = null) : KeyMaterial {
        var lastRecipient: String? = null
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? {
            lastRecipient = recipientPublicKeyBase64
            return (encryptResult ?: { it.reversedArray() })(plaintext)
        }
        override fun decryptMine(ciphertext: ByteArray): ByteArray? = ciphertext.reversedArray()
    }

    @Test fun withoutAnInnerKeyTheBodyIsLeftForTheOuterTransport() {
        val keys = FakeKeys()
        assertEquals("hello", InnerEnvelope.seal(keys, null, "hello"))
        assertNull(keys.lastRecipient)
    }

    @Test fun sealedBodyIsTypeEncWithStandardPaddedBase64OfTheCiphertext() {
        // RFC 4648 vectors: java.util.Base64 must give what the Android platform Base64 (NO_WRAP) gave.
        val vectors = mapOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, b64) in vectors) {
            val keys = FakeKeys(encryptResult = { plain.toByteArray() })
            assertEquals("TYPE:ENC|CT:$b64", InnerEnvelope.seal(keys, "KEY", "ignored"))
        }
    }

    @Test fun binaryCiphertextEncodesWithPlusSlashAndNoLineBreaks() {
        val ct = ByteArray(300) { (it * 37).toByte() }
        val sealed = InnerEnvelope.seal(FakeKeys(encryptResult = { ct }), "KEY", "x")
        val payload = sealed.removePrefix("TYPE:ENC|CT:")
        assertEquals(java.util.Base64.getEncoder().encodeToString(ct), payload)
        assertEquals(false, payload.contains('\n') || payload.contains('\r'))
        assertEquals(true, payload.contains('+') || payload.contains('/'))
    }

    @Test fun roundTripThroughOpen() {
        val keys = FakeKeys()
        val text = "h\u00e9llo \ud83d\udc4d | with pipe"
        val sealed = InnerEnvelope.seal(keys, "KEY", text)
        assertEquals("KEY", keys.lastRecipient)
        assertEquals(text, InnerEnvelope.open(keys, sealed))
    }

    @Test fun failedEncryptionToAKnownKeyAborts() {
        try {
            InnerEnvelope.seal(FakeKeys(encryptResult = { null }), "KEY", "secret")
            fail("must not fall back to the unsealed body")
        } catch (expected: IllegalStateException) {
            assertEquals("Couldn't encrypt this message; review the contact's key and try again", expected.message)
        }
    }

    @Test fun openRejectsWhatItCannotDecodeOrDecrypt() {
        val keys = FakeKeys()
        assertNull(InnerEnvelope.open(keys, "plain text"))
        assertNull(InnerEnvelope.open(keys, "TYPE:ENC|CT:!!!"))
        assertNull(InnerEnvelope.open(keys, "TYPE:ENC|CT:A")) // not decodable
        val refusing = object : KeyMaterial {
            override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray) = plaintext
            override fun decryptMine(ciphertext: ByteArray): ByteArray? = null
        }
        assertNull(InnerEnvelope.open(refusing, "TYPE:ENC|CT:Zm9v"))
    }

    @Test fun openAcceptsUnpaddedCiphertextLikeTheAndroidDecoderDid() {
        // Android's decoder accepted missing '=' padding; java.util.Base64 does as well.
        val keys = FakeKeys()
        assertEquals("oof", InnerEnvelope.open(keys, "TYPE:ENC|CT:Zm9v"))
        assertSame(null, InnerEnvelope.open(keys, "TYPE:ENC|CT:"))
    }
}
