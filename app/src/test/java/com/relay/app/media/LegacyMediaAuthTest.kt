package com.relay.app.media

import org.junit.Assert.*
import org.junit.Test
import com.relay.app.transport.mailbox.MailboxMedia
import com.relay.app.transport.mailbox.NostrSigner
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest

class LegacyMediaAuthTest {
    private val own = "a".repeat(64)
    private val contact = "b".repeat(64)

    @Test fun migratedMediaUsesExplicitPrimaryButLegacyOmitsQuery() {
        assertEquals("primary", mediaDevice(true))
        assertNull(mediaDevice(false))
    }

    @Test fun linkedSenderIsRecipientWithoutWideningToAnotherIdentity() {
        assertEquals(listOf(contact, own), mediaRecipients(listOf(contact, contact), own))
        assertEquals(listOf(own), mediaRecipients(listOf(own), own))
        assertThrows(IllegalArgumentException::class.java) { mediaRecipients((1..16).map { it.toString(16).padStart(64, '0') }, own) }
        assertThrows(IllegalArgumentException::class.java) { mediaRecipients(listOf("wrong"), own) }
    }

    @Test fun storedCiphertextMustFitServerCap() {
        assertEquals(26_214_400L, legacyStoredBytes(26_214_372L, false))
        assertThrows(IllegalArgumentException::class.java) { legacyStoredBytes(26_214_373L, false) }
        assertThrows(IllegalArgumentException::class.java) { legacyStoredBytes(26_214_372L, true) }
    }

    @Test fun originConfirmationWaitsForPublishedGrantButUnlinkedSenderDoesNotWait() {
        assertFalse(mediaOriginMayConfirm(false, true, false))
        assertFalse(mediaOriginMayConfirm(true, true, false))
        assertTrue(mediaOriginMayConfirm(true, true, true))
        assertTrue(mediaOriginMayConfirm(true, false, false))
    }

    private val base = "https://mailbox.example"
    private val blob = ByteArray(1000) { it.toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }

    private class Rec(val label: String, val log: MutableList<Triple<String, String, String>>) : NostrSigner {
        override fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String {
            val u = tags.first { it[0] == "u" }[1]; val m = tags.first { it[0] == "method" }[1]
            log.add(Triple(label, m, u)); return "{}"
        }
    }
    private fun http(seen: MutableList<String>) = OkHttpClient.Builder().addInterceptor { c ->
        val r = c.request(); seen.add(r.method + " " + r.url)
        val body = if (r.method == "GET") blob.toResponseBody() else ByteArray(0).toResponseBody()
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()
    }.build()

    private fun run(migrated: Boolean): Pair<List<Triple<String, String, String>>, List<String>> {
        val log = mutableListOf<Triple<String, String, String>>(); val seen = mutableListOf<String>()
        // The only signer handed to the factory is the per-device auth signer.
        val client = phoneMediaClient(Rec("device-auth", log), migrated, base, http(seen))
        client.upload(MailboxMedia.RawSource(blob.size.toLong()) { blob.inputStream() }, sha, listOf(contact, own))
        val f = java.io.File.createTempFile("wp08", ".bin").also { it.delete() }
        try { client.downloadTo(client.urlFor(sha), sha, f, 4096) } finally { f.delete() }
        assertTrue(client.confirm(client.urlFor(sha)) is MailboxMedia.Confirmation.Confirmed)
        return log to seen
    }

    @Test fun migratedPutGetDeleteSignExactDeviceUrlWithDeviceAuthSigner() {
        val (log, seen) = run(true)
        val url = "$base/v1/media/$sha?device=primary"
        assertEquals(listOf("PUT $url", "GET $url", "DELETE $url"), seen)
        assertEquals(listOf("PUT", "GET", "DELETE"), log.map { it.second })
        assertTrue(log.all { it.first == "device-auth" && it.third == url })
    }

    @Test fun beforeMigrationUrlStaysLegacyWithoutQuery() {
        val (log, seen) = run(false)
        val url = "$base/v1/media/$sha"
        assertEquals(listOf("PUT $url", "GET $url", "DELETE $url"), seen)
        assertTrue(log.all { it.third == url })
    }
}
