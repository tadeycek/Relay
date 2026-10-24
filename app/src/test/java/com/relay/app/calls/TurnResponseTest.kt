package com.relay.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /v1/call/ice` answers, including the honest failures (no TURN, rate limited, malformed). */
class TurnResponseTest {

    private val ok = """
        {"v":1,"iceServers":[
          {"urls":["stun:stun.cloudflare.com:3478"]},
          {"urls":["turn:turn.cloudflare.com:3478?transport=udp","turns:turn.cloudflare.com:443?transport=tcp"],
           "username":"u","credential":"c"}],
         "expiresAt":1790000000000}
    """.trimIndent()

    @Test fun theDocumentedSuccessShapeBecomesIceServers() {
        val result = TurnResponse.parse(200, ok)
        assertTrue(result is TurnResult.Ok)
        result as TurnResult.Ok
        assertEquals(2, result.servers.size)
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), result.servers[0].urls)
        assertEquals("u", result.servers[1].username)
        assertEquals("c", result.servers[1].credential)
        assertEquals(1790000000000L, result.expiresAtMs)
    }

    @Test fun aStunOnlyAnswerCannotCarryACallAndIsAFailure() {
        val stunOnly = """{"v":1,"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]}]}"""
        val result = TurnResponse.parse(200, stunOnly)
        assertTrue(result is TurnResult.Failed)
        assertEquals("no_turn_server", (result as TurnResult.Failed).code)
        assertFalse(result.retryable)
    }

    @Test fun theDocumentedErrorTableMapsToRetryability() {
        assertEquals(TurnResult.Failed("rate_limited", true), TurnResponse.parse(429, """{"v":1,"error":{"code":"rate_limited","retryable":true}}"""))
        assertEquals(TurnResult.Failed("upstream_failed", true), TurnResponse.parse(502, """{"v":1,"error":{"code":"upstream_failed","retryable":true}}"""))
        val notConfigured = TurnResponse.parse(503, """{"v":1,"error":{"code":"not_configured","retryable":false}}""") as TurnResult.Failed
        assertFalse(notConfigured.retryable)
        val revoked = TurnResponse.parse(401, """{"v":1,"error":{"code":"auth_revoked"}}""") as TurnResult.Failed
        assertEquals("auth_revoked", revoked.code)
        assertFalse(revoked.retryable)
    }

    @Test fun hostileAnswersNeverProduceAServerWithNoUsableUrl() {
        val junk = """{"v":1,"iceServers":[{"urls":["http://evil.example/",""]},{"urls":[]},{"nope":1}]}"""
        assertTrue(TurnResponse.parse(200, junk) is TurnResult.Failed)
        assertTrue(TurnResponse.parse(200, "not json") is TurnResult.Failed)
        assertTrue(TurnResponse.parse(200, """{"v":1}""") is TurnResult.Failed)
    }

    @Test fun credentialsLongerThanTheDocumentedBoundAreDroppedRatherThanStored() {
        val long = """{"v":1,"iceServers":[{"urls":["turn:turn.cloudflare.com:3478?transport=udp"],
            "username":"u","credential":"${"x".repeat(600)}"}]}"""
        val result = TurnResponse.parse(200, long) as TurnResult.Failed
        assertEquals("no_turn_server", result.code)
    }
}
