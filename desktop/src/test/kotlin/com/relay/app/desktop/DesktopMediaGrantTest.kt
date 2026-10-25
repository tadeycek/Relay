package com.relay.app.desktop

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Response-shape tests for `media` statuses on `POST /v1/sync` and `POST /v1/send` (PROTOCOL.md 106-111, 447-452). */
class DesktopMediaGrantTest {
    private val h = "e".repeat(64)
    private fun reply(status: String?, hash: String = h, complete: Boolean = true): JSONObject = JSONObject().put("v", 1).put("complete", complete)
        .also { if (status != null) it.put("media", org.json.JSONArray().put(JSONObject().put("hash", hash).put("status", status))) }

    @Test fun `attached and pending are accepted`() {
        assertNull(SyncWire.checkMediaReply(listOf(h), reply("attached")))
        assertNull(SyncWire.checkMediaReply(listOf(h), reply("pending")))
    }
    @Test fun `gone unknown status missing array and wrong hash all fail closed`() {
        assertNotNull(SyncWire.checkMediaReply(listOf(h), reply("gone")))
        assertNotNull(SyncWire.checkMediaReply(listOf(h), reply("weird")))
        assertNotNull(SyncWire.checkMediaReply(listOf(h), reply(null)))
        assertNotNull(SyncWire.checkMediaReply(listOf(h), reply("attached", hash = "f".repeat(64))))
        assertNotNull(SyncWire.checkMediaReply(listOf(h, "f".repeat(64)), reply("attached")))
    }
    @Test fun `an incomplete part is not judged and no media requested needs no array`() {
        assertNull(SyncWire.checkMediaReply(listOf(h), reply(null, complete = false)))
        assertNull(SyncWire.checkMediaReply(emptyList(), reply(null)))
    }

    private fun post(body: String): SyncHttpResult<Boolean> {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(200).setBody(body)); server.start()
        try {
            val keys = rust.nostr.sdk.Keys.generate()
            val http = DesktopSyncHttp(server.url("/").toString(), DesktopNostrSigner(keys), "d".repeat(32))
            return runBlocking { http.post(SyncWire.envelope("wire", 1, System.currentTimeMillis(), listOf(h))) }
        } finally { server.shutdown() }
    }
    @Test fun `real sync client treats a 200 with a gone grant as final rejection`() {
        val r = post("""{"v":1,"complete":true,"media":[{"hash":"$h","status":"gone"}]}""")
        assertTrue(r.toString(), r is SyncHttpResult.Rejected && r.final)
    }
    @Test fun `real sync client treats a 200 without the media array as final rejection`() {
        val r = post("""{"v":1,"complete":true}""")
        assertTrue(r.toString(), r is SyncHttpResult.Rejected && r.final)
    }
    @Test fun `real sync client accepts attached`() {
        assertEquals(SyncHttpResult.Ok(false), post("""{"v":1,"complete":true,"media":[{"hash":"$h","status":"attached"}]}"""))
    }
}
