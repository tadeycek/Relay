package com.relay.app.desktop

import com.relay.app.transport.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopCallGuardTest {
    private fun contact(qr: Boolean = true, deleted: Long? = null, trust: String = "trusted", nostr: String? = "b".repeat(64)) = DesktopContact(
        key = "k", nostrPubkey = nostr, name = "Bea", phone = "", hasRelay = true, trustLevel = trust, publicKey = null,
        pendingPublicKey = null, signingPublicKey = null, relayHints = emptyList(), qrVerified = qr, deletedAt = deleted, originalName = null, updatedAt = 0,
    )

    @Test fun onlyQrVerifiedNonBlockedNonDeletedContactsAreEligible() {
        assertEquals(true, DesktopCallEligibility.isEligible(contact()))
        assertEquals(false, DesktopCallEligibility.isEligible(contact(qr = false)))
        assertEquals(false, DesktopCallEligibility.isEligible(contact(deleted = 5)))
        assertEquals(false, DesktopCallEligibility.isEligible(contact(trust = "BLOCKED")))
        assertEquals(false, DesktopCallEligibility.isEligible(contact(trust = "blocked")))
        assertEquals(false, DesktopCallEligibility.isEligible(contact(nostr = null)))
    }

    @Test fun senderIsAllowedForOwnIdentityOrAnEligibleContactOnly() {
        val me = "a".repeat(64)
        val bea = "b".repeat(64)
        val filter = DesktopCallEligibility.senderFilter(me) { listOf(contact(), contact(qr = false, nostr = "c".repeat(64)), contact(trust = "BLOCKED", nostr = "d".repeat(64))) }
        assertEquals(true, filter(me))
        assertEquals(true, filter(bea))
        assertEquals(false, filter("c".repeat(64)))
        assertEquals(false, filter("d".repeat(64)))
        assertEquals(false, filter("e".repeat(64)))
    }

    @Test fun startIsRefusedWithAnHonestMessageWhileUnlinkedOrOffline() {
        val unlinked = DesktopCallGuard.startBlock(linked = false, hasMailbox = true, status = TransportStatus.ONLINE, eligible = true, group = false)
        assertNotNull(unlinked)
        assertEquals(true, unlinked!!.contains("Link this computer"))
        assertEquals(true, DesktopCallGuard.startBlock(true, false, TransportStatus.STOPPED, true, false)!!.contains("mailbox"))
        for (s in listOf(TransportStatus.OFFLINE, TransportStatus.CONNECTING, TransportStatus.STOPPED, TransportStatus.WAITING_FOR_TOR))
            assertEquals(true, DesktopCallGuard.startBlock(true, true, s, true, false)!!.contains("offline", ignoreCase = true))
        assertNotNull(DesktopCallGuard.startBlock(true, true, TransportStatus.ONLINE, false, false))
        assertNotNull(DesktopCallGuard.startBlock(true, true, TransportStatus.ONLINE, true, true))
        assertNull(DesktopCallGuard.startBlock(true, true, TransportStatus.ONLINE, true, false))
    }
}
