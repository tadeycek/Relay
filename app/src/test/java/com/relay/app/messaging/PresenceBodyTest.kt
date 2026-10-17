package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test

class PresenceBodyTest {
    private val now=2_000_000_000_000L
    @Test fun roundtripAndExpiryDoNotClaimPermanentOnlineState() {
        val status=PresenceBody.Status(now,true)
        assertEquals(status,PresenceBody.parse(PresenceBody.format(status),now))
        assertTrue(PresenceBody.active(status,now+59_999))
        assertFalse(PresenceBody.active(status,now+60_001))
        assertFalse(PresenceBody.active(status.copy(active=false),now))
    }
    @Test fun duplicateOrDelayedUpdatesCannotRestoreAnOlderStatus() {
        val stopped=PresenceBody.Status(now,false)
        assertFalse(PresenceBody.newer(stopped,PresenceBody.Status(now-1,true)))
        assertFalse(PresenceBody.newer(stopped,stopped.copy(active=true)))
        assertTrue(PresenceBody.newer(stopped,PresenceBody.Status(now+1,true)))
    }
    @Test fun rejectMalformedFutureAndExpiredControls() {
        assertNull(PresenceBody.parse(PresenceBody.PREFIX+"{}",now))
        assertNull(PresenceBody.parse(PresenceBody.format(PresenceBody.Status(now+5_001,true)),now))
        assertNull(PresenceBody.parse(PresenceBody.format(PresenceBody.Status(now-RetentionPolicy.LIFETIME_MS,false)),now))
        assertNull(PresenceBody.parse("Active now",now))
    }
    @Test fun unknownAndOfflineLabelsDoNotInventAConnection() {
        assertEquals("Last active unknown",PresenceBody.label(null,now))
        assertEquals("Last active 2 min ago",PresenceBody.label(PresenceBody.Status(now-120_000,true),now))
        assertEquals("Active now",PresenceBody.label(PresenceBody.Status(now,true),now))
    }
}
