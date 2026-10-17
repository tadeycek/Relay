package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test

class RetentionPolicyTest {
    private val now=2_000_000_000_000L
    @Test fun expiresAtSevenDaysNotBefore() {
        assertTrue(RetentionPolicy.expired(now-RetentionPolicy.LIFETIME_MS,now))
        assertFalse(RetentionPolicy.expired(now-RetentionPolicy.LIFETIME_MS+1,now))
    }
    @Test fun delayedMessagesDoNotRestartTheirLifetime() {
        assertTrue(RetentionPolicy.admissible(now-6*86_400_000L,now))
        assertFalse(RetentionPolicy.admissible(now-8*86_400_000L,now))
    }
    @Test fun farFutureTimestampCannotResurrectExpiredContent() {
        assertFalse(RetentionPolicy.admissible(Long.MAX_VALUE,now))
        assertFalse(RetentionPolicy.admissible(0,now))
    }
}
