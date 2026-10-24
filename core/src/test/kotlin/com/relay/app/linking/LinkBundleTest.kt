package com.relay.app.linking

import com.relay.app.crypto.KeyMaterial
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WP05-AC1 / LP-D06. A link bundle carries identity keys, contacts, groups and settings — never
 * message history — and is sealed with HPKE to the desktop's ephemeral link public key. The
 * one-time code is the retrieval proof only; it never participates in key transport.
 */
class LinkBundleTest {

    // --- test-only HPKE stand-in -------------------------------------------------------------
    // AES-GCM with a key derived from the *identity id* embedded in the key names. "pub:X" and
    // "priv:X" denote the two halves of the same ephemeral keypair X, which is the only property
    // LinkBundleCodec may rely on. Production implementations use Tink HPKE (WP06).
    private class FakeHpke : LinkHpke {
        var lastRecipient: String? = null
        var lastPlaintext: ByteArray? = null
        private fun key(name: String): ByteArray {
            val id = name.substringAfter(':')
            return MessageDigest.getInstance("SHA-256").digest(("relay-link-test:" + id).toByteArray())
        }
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? {
            lastRecipient = recipientPublicKeyBase64
            lastPlaintext = plaintext
            val iv = ByteArray(12) { 0x42 }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(recipientPublicKeyBase64), "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plaintext)
        }
        override fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray): ByteArray? = runCatching {
            val iv = ciphertext.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(recipientPrivateKeyBase64), "AES"), GCMParameterSpec(128, iv))
            c.doFinal(ciphertext, 12, ciphertext.size - 12)
        }.getOrNull()
    }

    private val hex64a = "a".repeat(64)
    private val hex64b = "b".repeat(64)
    private val key256 = Base64.getEncoder().encodeToString(ByteArray(256) { (it * 3).toByte() })

    private fun bundle() = LinkBundle(
        createdAt = 1_700_000_000_000L,
        identity = LinkIdentity(
            nostrSecretHex = "11".repeat(32),
            nostrPubkeyHex = hex64a,
            deviceId = "phone-primary",
            deviceLabel = "Pixel",
            hpkeKeysetBase64 = key256,
            hpkePreviousKeysetBase64 = Base64.getEncoder().encodeToString(ByteArray(40) { 7 }),
            hpkeKeysetExpiresAt = 1_700_086_400_000L,
        ),
        contacts = listOf(
            LinkContact(
                name = "Ann", phone = "+386 40 123 456", hasRelay = true, trustLevel = "trusted",
                publicKey = Base64.getEncoder().encodeToString(ByteArray(44) { it.toByte() }),
                signingPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
                nostrPubkey = hex64b, relayHints = listOf("wss://relay.example", "ws://abc.onion"),
                qrVerified = true,
            ),
            LinkContact(name = "Bob", trustLevel = "blocked", deletedAt = 1_700_000_000_001L, originalName = "Robert"),
        ),
        groups = listOf(
            LinkGroup("44444444-4444-4444-8444-444444444444", "Team", hex64a, listOf(hex64a, hex64b)),
            LinkGroup("55555555-5555-4555-8555-555555555555", "Empty"),
        ),
        settings = LinkSettings(
            typingIndicators = false,
            notificationPreview = true,
            retentionDays = 7,
            readReceipts = false,
            mediaAutoDownload = true,
            theme = "dark",
            locale = "sl-SI",
        ),
    )

    private fun assertOk(result: LinkResult): LinkBundle {
        if (result is LinkResult.Err) fail("expected Ok, got ${result.error}: ${result.detail}")
        return (result as LinkResult.Ok).bundle
    }

    private fun assertErr(expected: LinkError, result: LinkResult) {
        assertTrue("expected Err($expected), got $result", result is LinkResult.Err)
        assertEquals("error", expected, (result as LinkResult.Err).error)
    }

    // --- seal / open -------------------------------------------------------------------------

    @Test
    fun sealThenOpenWithTheMatchingDesktopKeyRoundTrips() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        // HPKE is addressed to the desktop's ephemeral public key, not derived from the link code.
        assertEquals("pub:desk-1", hpke.lastRecipient)
        val opened = assertOk(LinkBundleCodec.open(hpke, "priv:desk-1", sealed))
        assertEquals(bundle(), opened)
        println("BUNDLE SIZE: plaintext=${hpke.lastPlaintext!!.size} sealed=${sealed.size} cap=${LinkBundleCodec.MAX_BUNDLE_BYTES}")
        // The plaintext carried no link code (the code is proof, not a key): it only has the schema.
        val plain = hpke.lastPlaintext!!
        assertFalse(String(plain, Charsets.UTF_8).contains("RELAYLINK"))
    }

    @Test
    fun openWithTheWrongDesktopKeyFailsCleanly() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        assertErr(LinkError.CRYPTO_FAILED, LinkBundleCodec.open(hpke, "priv:desk-2", sealed))
    }

    @Test
    fun tamperedCiphertextOrTagIsRejected() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        val flipped = sealed.copyOf()
        flipped[flipped.size - 1] = (flipped.last().toInt() xor 1).toByte()
        assertErr(LinkError.CRYPTO_FAILED, LinkBundleCodec.open(hpke, "priv:desk-1", flipped))
        val middle = sealed.copyOf()
        middle[LinkBundleCodec.HEADER_BYTES + 20] = (middle[LinkBundleCodec.HEADER_BYTES + 20].toInt() xor 0x80).toByte()
        assertErr(LinkError.CRYPTO_FAILED, LinkBundleCodec.open(hpke, "priv:desk-1", middle))
    }

    @Test
    fun truncatedInputIsRejectedBeforeAnyCrypto() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        assertErr(LinkError.TRUNCATED, LinkBundleCodec.open(hpke, "priv:desk-1", sealed.copyOf(sealed.size - 1)))
        assertErr(LinkError.TRUNCATED, LinkBundleCodec.open(hpke, "priv:desk-1", sealed.copyOf(5)))
        assertErr(LinkError.TRUNCATED, LinkBundleCodec.open(hpke, "priv:desk-1", ByteArray(0)))
        // Declared length longer than the buffer.
        val lying = sealed.copyOf()
        // 65536 bytes declared, but the buffer only holds the short bundle.
        lying[6] = 0x00
        lying[7] = 0x01
        lying[8] = 0x00
        lying[9] = 0x00
        assertErr(LinkError.TRUNCATED, LinkBundleCodec.open(hpke, "priv:desk-1", lying))
    }

    @Test
    fun trailingBytesAreRejected() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        assertErr(LinkError.TRAILING_BYTES, LinkBundleCodec.open(hpke, "priv:desk-1", sealed + byteArrayOf(0)))
        assertErr(LinkError.TRAILING_BYTES, LinkBundleCodec.open(hpke, "priv:desk-1", sealed + ByteArray(16)))
    }

    @Test
    fun unknownVersionSuiteAndMagicAreRejected() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        val badVersion = sealed.copyOf(); badVersion[4] = 2
        assertErr(LinkError.UNSUPPORTED_VERSION, LinkBundleCodec.open(hpke, "priv:desk-1", badVersion))
        val badSuite = sealed.copyOf(); badSuite[5] = 9
        assertErr(LinkError.UNSUPPORTED_SUITE, LinkBundleCodec.open(hpke, "priv:desk-1", badSuite))
        val badMagic = sealed.copyOf(); badMagic[0] = 'X'.code.toByte()
        assertErr(LinkError.BAD_MAGIC, LinkBundleCodec.open(hpke, "priv:desk-1", badMagic))
    }

    @Test
    fun oversizeInputIsRejectedBeforeAllocationOrCrypto() {
        val hpke = FakeHpke()
        assertErr(LinkError.TOO_LARGE, LinkBundleCodec.open(hpke, "priv:desk-1", ByteArray(LinkBundleCodec.MAX_BUNDLE_BYTES + 1)))
        val hugeLength = ByteArray(LinkBundleCodec.HEADER_BYTES)
        "RLYB".toByteArray().copyInto(hugeLength)
        hugeLength[4] = 1; hugeLength[5] = 1
        hugeLength[6] = 0x7f; hugeLength[7] = 0xff.toByte(); hugeLength[8] = 0xff.toByte(); hugeLength[9] = 0xff.toByte()
        assertErr(LinkError.TOO_LARGE, LinkBundleCodec.open(hpke, "priv:desk-1", hugeLength))
    }

    @Test
    fun sealedBundleNeverExceedsTheServerSlotCap() {
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:desk-1", bundle())
        assertTrue(sealed.size <= LinkBundleCodec.MAX_BUNDLE_BYTES)
        // A bundle that would blow the 128 KiB slot is refused locally rather than at the server.
        val obese = bundle().copy(
            contacts = List(LinkBundleCodec.MAX_CONTACTS) {
                LinkContact(name = "N".repeat(LinkBundleCodec.MAX_NAME), phone = "1".repeat(64))
            },
        )
        try {
            LinkBundleCodec.seal(hpke, "pub:desk-1", obese)
            fail("expected LinkBundleException for an over-cap bundle")
        } catch (expected: LinkBundleException) {
            assertTrue(expected.message!!.contains("128"))
        }
    }

    // --- decoder robustness ------------------------------------------------------------------

    @Test
    fun everyFieldBoundIsEnforcedAtAndJustOverTheLimit() {
        val hpke = FakeHpke()
        fun accepted(b: LinkBundle) = runCatching { LinkBundleCodec.seal(hpke, "pub:d", b) }.isSuccess

        // name 100 / 101
        assertTrue(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n".repeat(LinkBundleCodec.MAX_NAME))))))
        assertFalse(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n".repeat(LinkBundleCodec.MAX_NAME + 1))))))

        // phone 64 / 65
        assertTrue(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n", phone = "p".repeat(64))))))
        assertFalse(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n", phone = "p".repeat(65))))))

        // device label 64 / 65
        assertTrue(accepted(bundle().copy(identity = bundle().identity.copy(deviceLabel = "d".repeat(64)))))
        assertFalse(accepted(bundle().copy(identity = bundle().identity.copy(deviceLabel = "d".repeat(65)))))

        // keyset base64 8192 / 8193
        assertTrue(accepted(bundle().copy(identity = bundle().identity.copy(hpkeKeysetBase64 = "A".repeat(8192)))))
        assertFalse(accepted(bundle().copy(identity = bundle().identity.copy(hpkeKeysetBase64 = "A".repeat(8193)))))

        // relay hints 5 / 6
        val hint = "wss://r.example"
        assertTrue(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n", relayHints = List(5) { hint })))))
        assertFalse(accepted(bundle().copy(contacts = listOf(LinkContact(name = "n", relayHints = List(6) { hint })))))

        // group members 256 / 257
        fun member(i: Int) = "%064x".format(i + 1)
        val gid = "66666666-6666-4666-8666-666666666666"
        assertTrue(accepted(bundle().copy(groups = listOf(LinkGroup(gid, "G", null, List(256) { member(it) })))))
        assertFalse(accepted(bundle().copy(groups = listOf(LinkGroup(gid, "G", null, List(257) { member(it) })))))

        // group name 100 / 101
        assertTrue(accepted(bundle().copy(groups = listOf(LinkGroup(gid, "g".repeat(100))))))
        assertFalse(accepted(bundle().copy(groups = listOf(LinkGroup(gid, "g".repeat(101))))))

        // contact count 1000 / 1001
        assertTrue(accepted(bundle().copy(contacts = List(1000) { LinkContact(name = "n") })))
        assertFalse(accepted(bundle().copy(contacts = List(1001) { LinkContact(name = "n") })))

        // settings theme 32 / 33
        assertTrue(accepted(bundle().copy(settings = LinkSettings(theme = "t".repeat(32)))))
        assertFalse(accepted(bundle().copy(settings = LinkSettings(theme = "t".repeat(33)))))

        // A maliciously bounded-but-too-large JSON is refused on decode with FIELD_TOO_LARGE, never
        // by letting org.json allocate unboundedly: the plaintext cap is checked first.
        val plain = LinkBundleCodec.encode(bundle())
        val over = plain.replace("\"deviceLabel\":\"Pixel\"", "\"deviceLabel\":\"${"d".repeat(65)}\"")
        assertErr(LinkError.FIELD_TOO_LARGE, LinkBundleCodec.decode(over))
    }

    @Test
    fun decodeRejectsOversizedPlaintextAndMalformedJson() {
        assertErr(LinkError.TOO_LARGE, LinkBundleCodec.decode("x".repeat(LinkBundleCodec.MAX_BUNDLE_BYTES + 1)))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(""))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode("{"))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode("[]"))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode("""{"v":1}"""))
        // Deep nesting is refused before org.json's recursive parser can exhaust the stack.
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode("[".repeat(10_000) + "]".repeat(10_000)))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode("\"unterminated"))
        // Unknown version on the inner schema, not just the outer envelope.
        assertErr(LinkError.UNSUPPORTED_VERSION, LinkBundleCodec.decode(LinkBundleCodec.encode(bundle()).replace("\"v\":1", "\"v\":2")))
    }

    @Test
    fun contactsGroupsAndSettingsRoundTripByteIdentically() {
        val plain = LinkBundleCodec.encode(bundle())
        val decoded = assertOk(LinkBundleCodec.decode(plain))
        assertEquals(bundle(), decoded)
        assertEquals(plain, LinkBundleCodec.encode(decoded))
        // And through the seal, byte-identical after open.
        val hpke = FakeHpke()
        val sealed = LinkBundleCodec.seal(hpke, "pub:d", bundle())
        val opened = assertOk(LinkBundleCodec.open(hpke, "priv:d", sealed))
        assertEquals(plain, LinkBundleCodec.encode(opened))
    }

    @Test
    fun noMessageHistoryIsEverIncludedOrAccepted() {
        // Compile-time: the bundle model has no field that could carry messages or media.
        val names = LinkBundle::class.java.declaredFields.map { it.name.lowercase() } +
            LinkIdentity::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { it.contains("message") || it.contains("history") || it.contains("media") })

        // Runtime: a producer that smuggles a history array is refused, not ignored.
        val plain = LinkBundleCodec.encode(bundle())
        val smuggled = plain.replaceFirst("{\"v\":1", "{\"v\":1,\"messages\":[{\"body\":\"secret\"}]")
        assertErr(LinkError.HISTORY_FORBIDDEN, LinkBundleCodec.decode(smuggled))
        val smuggled2 = plain.replaceFirst("{\"v\":1", "{\"v\":1,\"history\":[]")
        assertErr(LinkError.HISTORY_FORBIDDEN, LinkBundleCodec.decode(smuggled2))
        // Any other unknown top-level field is also refused (schema is closed).
        val unknown = plain.replaceFirst("{\"v\":1", "{\"v\":1,\"extra\":true")
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(unknown))
    }

    @Test
    fun invalidIdentityFieldsAreRefused() {
        val plain = LinkBundleCodec.encode(bundle())
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace(hex64a, "z".repeat(64))))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace("\"deviceId\":\"phone-primary\"", "\"deviceId\":\"\"")))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace("\"trustLevel\":\"trusted\"", "\"trustLevel\":\"admin\"")))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace("wss://relay.example", "https://relay.example")))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace("\"v\":1,\"createdAt\":1700000000000", "\"v\":1,\"createdAt\":-1")))
        assertErr(LinkError.MALFORMED, LinkBundleCodec.decode(plain.replace("\"retentionDays\":7", "\"retentionDays\":400")))
    }

    @Test
    fun linkCodeIsNotUsedAsEncryptionKeyTransport() {
        // The seal API has no code parameter at all. Two different codes sealing the same bundle to
        // the same desktop key produce ciphertexts of identical shape; only the recipient key matters.
        val hpke = FakeHpke()
        val a = LinkBundleCodec.seal(hpke, "pub:desk", bundle())
        val b = LinkBundleCodec.seal(hpke, "pub:desk", bundle())
        assertEquals(a.size, b.size)
        assertEquals("pub:desk", hpke.lastRecipient)
        assertNotNull(LinkBundleCodec.open(hpke, "priv:desk", a))
    }

    @Test
    fun keyMaterialSeamIsCompatibleWithLinkHpke() {
        // The phone can implement LinkHpke over the existing KeyMaterial seam without a new crypto
        // dependency: encryptTo has exactly the same shape. (decryptWith needs the ephemeral private
        // key, which KeyMaterial deliberately does not expose.)
        val km = object : KeyMaterial {
            override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? =
                plaintext.reversedArray()
            override fun decryptMine(ciphertext: ByteArray): ByteArray? = null
        }
        val adapter = object : LinkHpke {
            override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray) =
                km.encryptTo(recipientPublicKeyBase64, plaintext)
            override fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray) =
                ciphertext.reversedArray()
        }
        val sealed = LinkBundleCodec.seal(adapter, "pub:x", bundle())
        assertTrue(LinkBundleCodec.open(adapter, "priv:x", sealed) is LinkResult.Ok)
        assertTrue(LinkBundleCodec.open(adapter, "priv:x", sealed.copyOf(sealed.size - 1)) is LinkResult.Err)
    }
}
