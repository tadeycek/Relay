package com.relay.app.calls.group

import com.relay.app.call.IceServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group SFU peer must build its ICE configuration from the issued TURN answer (the same per-device
 * `GET /v1/call/ice` the 1:1 calls use), keep STUN as fallback, never invent credentials and refuse to
 * publish when the relay is required but unavailable.
 */
class GroupSfuIceTest {

    private fun turn(vararg urls: String, user: String? = "issued-user", cred: String? = "issued-credential") =
        IceServer(urls.toList(), user, cred)

    @Test fun keepsBothTurnTransportsAndTheIssuedCredentials() {
        val plan = GroupSfuIce.plan(listOf(
            IceServer(listOf(GroupSfuIce.STUN_FALLBACK)),
            turn("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"),
        ), requireTurn = true)!!
        assertEquals(2, plan.size)
        val turnEntry = plan.single { it.isTurn }
        assertEquals(listOf("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"), turnEntry.urls)
        assertEquals("issued-user", turnEntry.username)
        assertEquals("issued-credential", turnEntry.credential)
        // The STUN entry the API issued is kept as-is, no duplicate fallback is added.
        assertEquals(listOf(GroupSfuIce.STUN_FALLBACK), plan.single { !it.isTurn }.urls)
        assertNull(plan.single { !it.isTurn }.username)
    }

    @Test fun stunOnlyIsRejectedWhenTheRelayIsRequiredButFineAsFallback() {
        val stunOnly = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478")))
        assertNull("no TURN means a strict-NAT peer can never be reached", GroupSfuIce.plan(stunOnly, requireTurn = true))
        assertNull(GroupSfuIce.plan(emptyList(), requireTurn = true))
        val lenient = GroupSfuIce.plan(stunOnly, requireTurn = false)!!
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), lenient.single().urls)
    }

    @Test fun aTurnEntryWithoutCredentialsIsDroppedNotUsed() {
        val plan = GroupSfuIce.plan(listOf(IceServer(listOf("turn:relay.example:3478"), null, null)), requireTurn = true)
        assertNull(plan)
        val mixed = GroupSfuIce.plan(listOf(
            IceServer(listOf("turn:relay.example:3478"), "u", null),
            turn("turns:relay.example:5349"),
        ), requireTurn = true)!!.single { it.isTurn }
        assertEquals(listOf("turns:relay.example:5349"), mixed.urls)
        assertEquals("issued-user", mixed.username)
    }

    @Test fun addsTheStunFallbackWhenTheAnswerHasNoStunOfItsOwn() {
        val plan = GroupSfuIce.plan(listOf(turn("turn:relay.example:3478")), requireTurn = true)!!
        assertEquals(2, plan.size)
        assertTrue(plan.any { it.urls == listOf(GroupSfuIce.STUN_FALLBACK) && it.username == null && it.credential == null })
    }

    @Test fun hostileUrlsAreFilteredAndBoundsAreEnforced() {
        val plan = GroupSfuIce.plan(listOf(
            IceServer(listOf("http://evil.example/x", "turn:relay.example:3478 "), "u", "c"),
            IceServer(List(20) { "turn:relay$it.example:3478" }, "u", "c"),
            IceServer(listOf("stun:stun.example:3478"), "u".repeat(600), "c"),
        ), requireTurn = false)!!
        assertFalse("an http url must never reach the native ICE config", plan.any { s -> s.urls.any { it.startsWith("http") } })
        assertTrue(plan.all { it.urls.size <= 8 })
        assertTrue(plan.all { (it.username?.length ?: 0) <= 512 })
        // The hostile first server has no usable url left, the 20-url one is capped, the long username is dropped.
        assertTrue(plan.any { it.urls == listOf("stun:stun.example:3478") && it.username == null })
        assertTrue(plan.filter { it.isTurn }.all { it.urls.all { u -> u.startsWith("turn:relay") } })
    }

    @Test fun toStringNeverPrintsTheCredential() {
        val entry = GroupSfuIce.plan(listOf(turn("turn:relay.example:3478")), requireTurn = true)!!.single { it.isTurn }
        val text = entry.toString()
        assertFalse(text.contains("issued-credential"))
        assertFalse(text.contains("issued-user"))
        assertTrue(text.contains("<redacted>"))
    }
}
