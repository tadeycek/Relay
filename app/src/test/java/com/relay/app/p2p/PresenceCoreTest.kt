package com.relay.app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceCoreTest {

    private val nonce = "0123456789abcdef0123456789abcdef"

    // ---- messages -------------------------------------------------------------------------------

    @Test
    fun aPingRoundTrips() {
        val parsed = PresenceMessages.parse(PresenceMessages.formatPing(nonce)) as PresenceMessage.Ping
        assertEquals(nonce, parsed.nonce)
    }

    @Test
    fun aPongRoundTripsWithCandidates() {
        val candidates = listOf("192.168.1.12:41200", "[fe80::1234:abcd]:41200")
        val parsed = PresenceMessages.parse(PresenceMessages.formatPong(nonce, candidates)) as PresenceMessage.Pong
        assertEquals(nonce, parsed.nonce)
        assertEquals(candidates, parsed.candidates)
    }

    @Test
    fun aPongWithNoCandidatesRoundTripsEmpty() {
        val parsed = PresenceMessages.parse(PresenceMessages.formatPong(nonce, emptyList())) as PresenceMessage.Pong
        assertTrue(parsed.candidates.isEmpty())
    }

    @Test
    fun invalidCandidatesAreDroppedNotCrashed() {
        val body = PresenceMessages.formatPong(nonce, listOf("192.168.1.12:41200", "not-an-address", "8.8.8.8:99999"))
        val parsed = PresenceMessages.parse(body) as PresenceMessage.Pong
        assertEquals(listOf("192.168.1.12:41200"), parsed.candidates)
    }

    @Test
    fun duplicateAndExcessCandidatesAreTrimmed() {
        val many = (1..10).map { "10.0.0.$it:41200" } + "10.0.0.1:41200"
        val body = PresenceMessages.formatPong(nonce, many)
        val parsed = PresenceMessages.parse(body) as PresenceMessage.Pong
        assertTrue(parsed.candidates.size <= 6)
        assertEquals(parsed.candidates.size, parsed.candidates.distinct().size)
    }

    @Test
    fun malformedOrHostileBodiesAreRejected() {
        val bad = listOf(
            "",
            "hello",
            "TYPE:PING",
            "TYPE:PING|NONCE:short",
            "TYPE:PING|NONCE:${nonce.uppercase()}",
            "TYPE:LOCATION_REQUEST",
        )
        for (b in bad) assertNull("should reject: ${b.take(40)}", PresenceMessages.parse(b))
    }

    @Test
    fun aHugeSingleCandidateIsDroppedRatherThanCrashingOrBloatingMemory() {
        val body = "TYPE:PONG|NONCE:$nonce|ADDR:" + "x".repeat(10_000)
        val parsed = PresenceMessages.parse(body) as PresenceMessage.Pong
        assertTrue(parsed.candidates.isEmpty())
    }

    @Test
    fun ordinaryTextAndOtherControlMessagesAreNotPresenceMessages() {
        assertFalse(PresenceMessages.isPresenceMessage("see you at 5"))
        assertFalse(PresenceMessages.isPresenceMessage("TYPE:PUBKEY|KEY:S0VZ"))
        assertFalse(PresenceMessages.isPresenceMessage("TYPE:PAIR_REQUEST|NONCE:$nonce|KEY:S0VZ"))
    }

    @Test
    fun generatedNoncesAreValidAndDifferent() {
        val a = PresenceMessages.randomNonce()
        val b = PresenceMessages.randomNonce()
        assertTrue(PresenceMessages.isValidNonce(a))
        assertNotEquals(a, b)
    }

    @Test
    fun candidateValidation() {
        assertTrue(PresenceMessages.isValidCandidate("192.168.1.1:8080"))
        assertTrue(PresenceMessages.isValidCandidate("[::1]:8080"))
        assertFalse(PresenceMessages.isValidCandidate("192.168.1.1"))
        assertFalse(PresenceMessages.isValidCandidate("not an address:8080"))
        assertFalse(PresenceMessages.isValidCandidate("192.168.1.1:123456"))
    }

    // ---- timing ---------------------------------------------------------------------------------

    @Test
    fun timeoutIsInclusive() {
        assertFalse(PresenceRules.isExpired(1000L, 1000L))
        assertTrue(PresenceRules.isExpired(1000L, 1001L))
    }
}
