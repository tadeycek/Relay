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

    @Test fun largestPhotoStillFitsInsideAnEncryptedTransportMessage() {
        val photo = ByteArray(ProfilePhotoBody.MAX_BYTES).apply { this[0] = -1; this[1] = -40; this[2] = -1 }
        val body = ProfilePhotoBody.format(ProfilePhotoBody.Update(Long.MAX_VALUE, photo))
        assertNotNull(ProfilePhotoBody.parse(body))
        // Inner HPKE ciphertext + base64 expansion, header and key overhead still fit 16 KiB.
        assertTrue((body.length + 512) * 4 / 3 < com.relay.app.transport.PayloadCodec.MAX_BODY_LENGTH)
    }
}
