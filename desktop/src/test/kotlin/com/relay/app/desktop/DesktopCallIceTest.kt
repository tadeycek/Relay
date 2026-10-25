package com.relay.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /v1/call/ice` answer validation: hostile or malformed answers become a failure, never a peer with no relay. */
class DesktopCallIceTest {
    private fun ok(extra: String = "") = """{"v":1,"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},
        {"urls":["turn:turn.cloudflare.com:3478?transport=udp","turns:turn.cloudflare.com:443?transport=tcp"],"username":"u","credential":"c"}]$extra,"expiresAt":1790000000000}"""

    @Test fun validAnswerYieldsServersAndExpiry() {
        val r = DesktopIceResponse.parse(200, ok()) as DesktopIceResponse.Ok
        assertEquals(2, r.servers.size)
        assertEquals(1790000000000L, r.expiresAtMs)
        assertEquals("u", r.servers[1].username)
    }
    @Test fun nonOkStatusFails() = assertTrue(DesktopIceResponse.parse(503, """{"error":{"code":"not_configured"}}""") is DesktopIceResponse.Failed)
    @Test fun statusZeroOrNullBodyFails() {
        assertTrue(DesktopIceResponse.parse(0, null) is DesktopIceResponse.Failed)
        assertTrue(DesktopIceResponse.parse(200, null) is DesktopIceResponse.Failed)
    }
    @Test fun missingExpiryFails() = assertTrue(DesktopIceResponse.parse(200, """{"iceServers":[{"urls":["turn:a.cloudflare.com:3478"],"username":"u","credential":"c"}]}""") is DesktopIceResponse.Failed)
    @Test fun answerWithoutAnyTurnCredentialFails() =
        assertTrue(DesktopIceResponse.parse(200, """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]}],"expiresAt":1790000000000}""") is DesktopIceResponse.Failed)
    @Test fun unknownSchemesAndOverlongCredentialsAreDropped() {
        val body = """{"iceServers":[{"urls":["http://evil.example/x","file:///etc/passwd"]},
            {"urls":["turn:a.cloudflare.com:3478"],"username":"${"u".repeat(600)}","credential":"c"},
            {"urls":["turn:b.cloudflare.com:3478"],"username":"u","credential":"c"}],"expiresAt":1790000000000}"""
        val r = DesktopIceResponse.parse(200, body) as DesktopIceResponse.Ok
        assertEquals(listOf("turn:b.cloudflare.com:3478"), r.servers.flatMap { it.urls }.filter { it.startsWith("turn") })
        assertTrue(r.servers.none { it.urls.any { u -> u.startsWith("http") || u.startsWith("file") } })
        assertTrue(r.servers.none { (it.username?.length ?: 0) > 512 })
    }
    @Test fun serverAndUrlCountsAreBounded() {
        val urls = (1..20).joinToString(",") { "\"turn:h$it.cloudflare.com:3478\"" }
        val servers = (1..20).joinToString(",") { """{"urls":[$urls],"username":"u","credential":"c"}""" }
        val r = DesktopIceResponse.parse(200, """{"iceServers":[$servers],"expiresAt":1790000000000}""") as DesktopIceResponse.Ok
        assertEquals(8, r.servers.size)
        assertTrue(r.servers.all { it.urls.size <= 8 })
    }
    @Test fun failureTextNeverContainsTheCredential() {
        val r = DesktopIceResponse.parse(200, """{"iceServers":[{"urls":["turn:a.cloudflare.com:3478"],"username":"SECRETUSER","credential":"SECRETCRED"}]}""") as DesktopIceResponse.Failed
        assertTrue(!r.toString().contains("SECRET"))
    }
}
