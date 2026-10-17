package com.relay.app.ui

import com.relay.app.ui.screens.chat.trustLineFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustLineTest {
    @Test
    fun verifiedInPersonIsTheOnlyVerifiedLine() {
        assertTrue(trustLineFor(hasKey = true, verifiedInPerson = true).verified)
        assertFalse(trustLineFor(hasKey = true, verifiedInPerson = false).verified)
        assertFalse(trustLineFor(hasKey = false, verifiedInPerson = true).verified)
    }

    @Test
    fun aContactWithoutAKeyIsToldToScan() {
        assertTrue(trustLineFor(hasKey = false, verifiedInPerson = false).text.contains("scan", ignoreCase = true))
    }
}
