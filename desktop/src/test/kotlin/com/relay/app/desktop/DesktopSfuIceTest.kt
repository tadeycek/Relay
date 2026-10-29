package com.relay.app.desktop

import com.relay.app.call.IceServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The group SFU peer's ICE configuration comes from the issued per-device TURN answer (the same
 * `GET /v1/call/ice` the 1:1 calls use): TURN with its short-lived credentials, UDP and TLS when issued,
 * STUN only as fallback, and an honest refusal when the relay is required but missing.
 */
class DesktopSfuIceTest {

    private fun turn(vararg urls: String, user: String? = "cf-user", cred: String? = "cf-credential") =
        IceServer(urls.toList(), user, cred)

    @Test
    fun mapsTurnWithBothTransportsAndKeepsCredentials() {
        val plan = SfuIcePlan.build(listOf(
            IceServer(listOf("stun:stun.cloudflare.com:3478")),
            turn("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"),
        ), requireTurn = true)!!
        assertEquals(2, plan.size)
        val t = plan.single { it.isTurn }
        assertEquals("cf-user", t.username)
        assertEquals("cf-credential", t.credential)
        assertEquals(2, t.urls.size)
    }

    @Test
    fun refusesWhenTurnIsRequiredButOnlyStunWasIssued() {
        assertNull(SfuIcePlan.build(listOf(IceServer(listOf("stun:stun.cloudflare.com:3478"))), requireTurn = true))
        assertNull(SfuIcePlan.build(emptyList(), requireTurn = true))
        assertNotNull(SfuIcePlan.build(emptyList(), requireTurn = false))
    }

    @Test
    fun dropsTurnEntriesWithoutCredentials() {
        assertNull(SfuIcePlan.build(listOf(IceServer(listOf("turn:relay.example:3478"), null, null)), requireTurn = true))
        assertNull(SfuIcePlan.build(listOf(IceServer(listOf("turn:relay.example:3478"), "u", "")), requireTurn = true))
    }

    @Test
    fun stunFallbackIsAppendedOnlyWhenMissingAndIsConfigurable() {
        val withTurn = SfuIcePlan.build(listOf(turn("turn:relay.example:3478")), requireTurn = true)!!
        assertTrue(withTurn.any { it.urls == listOf(SfuIcePlan.STUN_FALLBACK) && it.username == null })

        val custom = SfuIcePlan.build(listOf(turn("turn:relay.example:3478")), requireTurn = false,
            fallbackStun = listOf("stun:internal.example:3478"))!!
        assertTrue(custom.any { it.urls == listOf("stun:internal.example:3478") })

        val none = SfuIcePlan.build(emptyList(), requireTurn = false, fallbackStun = emptyList())
        assertTrue("an explicit empty fallback list means no ICE servers, as before", none!!.isEmpty())
    }

    @Test
    fun hostileUrlsAndOversizedCredentialsAreFiltered() {
        val plan = SfuIcePlan.build(listOf(
            IceServer(listOf("http://evil.example", "turn:relay.example:3478 "), "u", "c"),
            IceServer(List(20) { "turn:relay$it.example:3478" }, "u", "c"),
            IceServer(listOf("stun:stun.example:3478"), "u".repeat(600), "c"),
        ), requireTurn = false)!!
        assertFalse(plan.any { s -> s.urls.any { it.startsWith("http") } })
        assertTrue(plan.all { it.urls.size <= 8 })
        assertTrue(plan.filter { it.isTurn }.all { it.urls.all { u -> u.startsWith("turn:relay") } })
    }

    @Test
    fun toStringNeverLeaksTheCredential() {
        val entry = SfuIcePlan.build(listOf(turn("turn:relay.example:3478")), requireTurn = true)!!.single { it.isTurn }
        val text = entry.toString()
        assertFalse(text.contains("cf-credential"))
        assertFalse(text.contains("cf-user"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun cachesTheIssuedAnswerUntilShortlyBeforeItExpires() {
        val fetches = AtomicInteger()
        var now = 1_000_000L
        val link = object : DesktopCallLink {
            override var callRouter: DesktopCallRouter? = null
            override fun isOnline() = true
            override suspend fun signal(to: String, payload: com.relay.app.transport.RelayPayload) =
                com.relay.app.transport.SendResult.Failed("unused", false)
            override suspend fun deliverUrgent(to: String, payload: com.relay.app.transport.RelayPayload) =
                com.relay.app.transport.SendResult.Failed("unused", false)
            override fun fetchIce(reply: (status: Int, body: String?) -> Unit): AutoCloseable {
                fetches.incrementAndGet()
                val body = """{"v":1,"iceServers":[{"urls":["turn:relay.example:3478"],"username":"u","credential":"c"}],"expiresAt":${now + 600_000}}"""
                reply(200, body)
                return AutoCloseable { }
            }
        }
        val ice = DesktopGroupIce(link, nowMs = { now })
        assertEquals(1, ice.get().size)
        assertEquals(1, ice.get().size)
        assertEquals("a warm cache must not mint another credential", 1, fetches.get())
        now += 600_000 // past expiry
        ice.get()
        assertEquals(2, fetches.get())
        assertFalse(ice.toString().contains("relay.example"))
    }
}
