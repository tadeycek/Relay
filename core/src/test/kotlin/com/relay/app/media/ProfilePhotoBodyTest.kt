package com.relay.app.media

import org.junit.Assert.*
import org.junit.Test

class ProfilePhotoBodyTest {
    @Test fun jpegAndRemovalRoundTripWithRevision() {
        val photo = byteArrayOf(-1, -40, -1, 42)
        val parsed = ProfilePhotoBody.parse(ProfilePhotoBody.format(ProfilePhotoBody.Update(7, photo)))!!
        assertEquals(7L, parsed.revision)
        assertArrayEquals(photo, parsed.jpeg)
        assertNull(ProfilePhotoBody.parse(ProfilePhotoBody.format(ProfilePhotoBody.Update(8, null)))!!.jpeg)
    }

    @Test fun hostileOrMalformedPhotoMessagesAreRejected() {
        for (body in listOf("TYPE:PROFILE_PHOTO|REV:-1|JPEG:", "TYPE:PROFILE_PHOTO|REV:NaN|JPEG:",
            "TYPE:PROFILE_PHOTO|REV:1|JPEG:!!!", "TYPE:PROFILE_PHOTO|REV:1|JPEG:aGVsbG8=",
            "TYPE:PROFILE_PHOTO|REV:1|JPEG:|REV:2", "TYPE:PROFILE_PHOTO|REV:1|JPEG:" + "A".repeat(9_000))) {
            assertTrue(ProfilePhotoBody.isControl(body))
            assertNull(body.take(70), ProfilePhotoBody.parse(body))
        }
        assertNull(ProfilePhotoBody.parse(ProfilePhotoBody.REQUEST))
    }

    @Test fun realHpkeEnvelopeAtMaximumPhotoSizeRoundTripsWithinExistingLimit() {
        com.google.crypto.tink.config.TinkConfig.register()
        val keys = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))
        val photo = ByteArray(ProfilePhotoBody.MAX_BYTES).apply { this[0] = -1; this[1] = -40; this[2] = -1 }
        val body = ProfilePhotoBody.format(ProfilePhotoBody.Update(Long.MAX_VALUE, photo))
        val cipher = keys.publicKeysetHandle.getPrimitive(com.google.crypto.tink.HybridEncrypt::class.java).encrypt(body.toByteArray(), null)
        val sealed = com.relay.app.util.SmsMessageParser.formatEncrypted(java.util.Base64.getEncoder().encodeToString(cipher))
        assertTrue(sealed.length <= com.relay.app.transport.PayloadCodec.MAX_BODY_LENGTH)
        val payload = com.relay.app.transport.RelayPayload("photo", 123, sealed)
        assertEquals(payload, com.relay.app.transport.PayloadCodec.decode(com.relay.app.transport.PayloadCodec.encode(payload)))
        val plain = keys.getPrimitive(com.google.crypto.tink.HybridDecrypt::class.java).decrypt(cipher, null)
        assertEquals(body, String(plain))
        assertArrayEquals(photo, ProfilePhotoBody.parse(String(plain))!!.jpeg)
    }

    @Test fun largestPhotoStillFitsInsideAnEncryptedTransportMessage() {
        val photo = ByteArray(ProfilePhotoBody.MAX_BYTES).apply { this[0] = -1; this[1] = -40; this[2] = -1 }
        val body = ProfilePhotoBody.format(ProfilePhotoBody.Update(Long.MAX_VALUE, photo))
        assertNotNull(ProfilePhotoBody.parse(body))
        // Inner HPKE ciphertext + base64 expansion, header and key overhead still fit 16 KiB.
        assertTrue((body.length + 512) * 4 / 3 < com.relay.app.transport.PayloadCodec.MAX_BODY_LENGTH)
    }
}
