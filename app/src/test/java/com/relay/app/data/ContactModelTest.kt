package com.relay.app.data

import com.relay.app.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactModelTest {

    private val hex = "0123456789abcdef".repeat(4)

    @Test
    fun legacyPhoneContactShowsItsNumber() {
        val c = Contact(name = "Old", phone = "+38640111222")
        assertTrue(c.hasPhone)
        assertFalse(c.canUseInternetTransport)
        assertEquals("+38640111222", c.subtitle)
    }

    @Test
    fun internetContactUsesPlaceholderPhoneAndShowsRelayId() {
        val c = Contact(name = "Ana", phone = Contact.placeholderPhoneFor(hex), nostrPubkey = hex, qrVerified = true)
        assertFalse(c.hasPhone)
        assertTrue(c.canUseInternetTransport)
        assertEquals("Relay ID 01234567…cdef", c.subtitle)
    }

    @Test
    fun unverifiedInternetContactIsFlaggedEvenWithAKeyLearnedFromTheirMessage() {
        val c = Contact(
            name = "Mom", phone = Contact.placeholderPhoneFor(hex), nostrPubkey = hex,
            publicKey = "AAAA", qrVerified = false,
        )
        assertEquals("Relay ID 01234567…cdef · unverified", c.subtitle)
    }

    @Test
    fun placeholderPhoneIsUniquePerKey() {
        assertTrue(Contact.placeholderPhoneFor("a".repeat(64)) != Contact.placeholderPhoneFor("b".repeat(64)))
        assertTrue(Contact.placeholderPhoneFor(hex).startsWith(Contact.NOSTR_PHONE_PREFIX))
    }
}
