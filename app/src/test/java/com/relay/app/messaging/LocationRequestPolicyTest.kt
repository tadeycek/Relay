package com.relay.app.messaging

import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.messaging.LocationRequestPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRequestPolicyTest {

    private fun decide(
        fromNobody: Boolean = false,
        autoApproveAll: Boolean = false,
        trust: ContactTrustLevel = ContactTrustLevel.ASK,
        verified: Boolean = true,
        dnd: Boolean = false,
    ) = LocationRequestPolicy.decide(fromNobody, autoApproveAll, trust, verified, dnd)

    @Test
    fun nobodyBlockedAndDndAlwaysDrop() {
        assertEquals(Decision.DROP, decide(fromNobody = true, trust = ContactTrustLevel.TRUSTED))
        assertEquals(Decision.DROP, decide(trust = ContactTrustLevel.BLOCKED, autoApproveAll = true))
        assertEquals(Decision.DROP, decide(dnd = true, trust = ContactTrustLevel.TRUSTED, autoApproveAll = true))
    }

    @Test
    fun defaultIsToAsk() {
        assertEquals(Decision.ASK, decide())
    }

    @Test
    fun trustedContactsAreSharedWithAutomatically() {
        assertEquals(Decision.AUTO_SHARE, decide(trust = ContactTrustLevel.TRUSTED))
        assertEquals("even if unverified: the user chose Trusted deliberately",
            Decision.AUTO_SHARE, decide(trust = ContactTrustLevel.TRUSTED, verified = false))
    }

    @Test
    fun autoApproveOnlyAppliesToVerifiedContacts() {
        assertEquals(Decision.AUTO_SHARE, decide(autoApproveAll = true, verified = true))
        assertEquals(
            "a stranger who knows our key must never get an automatic share",
            Decision.ASK, decide(autoApproveAll = true, verified = false),
        )
    }
}
