package com.relay.app.media

import org.junit.Assert.*
import org.junit.Test

class ProfileNameBodyTest {
    @Test fun previousClientsConsumeTheNameExtensionAsUnsupportedProfileMetadata() {
        val body = ProfileNameBody.format(ProfileNameBody.Update(1, "Exion"))
        assertTrue(ProfilePhotoBody.isControl(body))
        assertNull(ProfilePhotoBody.parse(body))
    }
    @Test fun unicodeNamesRoundTripWithoutFieldInjection() {
        val update = ProfileNameBody.Update(4, "Živa | NAME:🌱")
        assertEquals(update, ProfileNameBody.parse(ProfileNameBody.format(update)))
    }
    @Test fun rejectsMalformedOversizedAndControlNames() {
        for (name in listOf("", " x", "x\nY", "x\u202eY", "x".repeat(31))) {
            assertTrue(runCatching { ProfileNameBody.format(ProfileNameBody.Update(1, name)) }.isFailure)
        }
        for (body in listOf("TYPE:PROFILE_PHOTO|KIND:NAME|REV:-1|NAME:QQ==", "TYPE:PROFILE_PHOTO|KIND:NAME|REV:1|NAME:/w==",
            "TYPE:PROFILE_PHOTO|KIND:NAME|REV:1|NAME:QQ==|extra", "TYPE:PROFILE_PHOTO|KIND:NAME|" + "A".repeat(257))) {
            assertNull(ProfileNameBody.parse(body))
        }
    }
}
