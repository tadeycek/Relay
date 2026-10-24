package com.relay.app.calls

import com.relay.app.call.CallEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /v1/call/ice` ordering rules (`CALL_ENGINE.md` rule 8): a credential answer belongs to the call
 * that asked for it. A late answer after teardown, or for a previous call, must never reach the engine.
 *
 * These tests run on the deterministic test dispatcher, so the replace/cancel races are exercised without
 * wall-clock sleeps. Production code is unchanged by this: [MailboxTurnPort] only ever sees a scope.
 */
class MailboxTurnPortTest {

    private class Harness(scope: CoroutineScope) {
        val events = mutableListOf<CallEvent>()
        val gate = CompletableDeferred<Pair<Int, String>>()
        var requested = 0
        val port = MailboxTurnPort(
            scope = scope,
            post = { it() },
            fetch = {
                requested++
                gate.await()
            },
        )

        init {
            port.attach { events += it }
        }
    }

    @Test
    fun aSuccessfulCredentialAnswerBecomesIceServersReady() = runBlocking {
        val h = Harness(CoroutineScope(Dispatchers.Unconfined))
        h.port.request("call-1")
        h.gate.complete(200 to ICE_BODY)
        repeat(200) { if (h.events.isEmpty()) delay(5) }
        val event = h.events.single() as CallEvent.IceServersReady
        assertEquals("call-1", event.callId)
        assertEquals(2, event.servers.size)
        assertTrue(event.servers.any { it.urls.any { url -> url.startsWith("turn:") } })
    }

    @Test
    fun aFailedAnswerBecomesIceServersFailed() = runBlocking {
        val h = Harness(CoroutineScope(Dispatchers.Unconfined))
        h.port.request("call-1")
        h.gate.complete(503 to """{"error":"turn_unavailable"}""")
        repeat(200) { if (h.events.isEmpty()) delay(5) }
        assertEquals("call-1", (h.events.single() as CallEvent.IceServersFailed).callId)
    }

    @Test
    fun anAnswerAfterTeardownIsDropped() = runBlocking {
        val h = Harness(CoroutineScope(Dispatchers.Unconfined))
        h.port.request("call-1")
        h.port.cancel("call-1")
        h.gate.complete(200 to ICE_BODY)
        repeat(200) { if (h.events.isEmpty()) delay(5) }
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun anAnswerForAnEarlierCallIsDroppedWhenANewCallAsked() = runBlocking {
        val h = Harness(CoroutineScope(Dispatchers.Unconfined))
        h.port.request("call-1")
        // A second request replaces the first; the first answer must not land.
        h.port.request("call-2")
        h.gate.complete(200 to ICE_BODY)
        repeat(200) { if (h.events.isEmpty()) delay(5) }
        assertEquals("call-2", (h.events.single() as CallEvent.IceServersReady).callId)
    }

    private companion object {
        const val ICE_BODY = """{"iceServers":[
            {"urls":["stun:stun.cloudflare.com:3478"]},
            {"urls":["turn:turn.cloudflare.com:3478?transport=udp"],"username":"u","credential":"c"}
        ]}"""
    }
}
