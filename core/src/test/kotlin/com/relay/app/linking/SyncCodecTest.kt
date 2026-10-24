package com.relay.app.linking

import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * G3 / 05 §3. `TYPE:SYNC` bodies are self-sync only. Every body carries a version, a unique sync
 * id, a monotonic revision and the origin device, and must fit the existing 16 KiB mailbox
 * envelope **after base64** — a maximum-size SENT event is measured, not estimated.
 */
class SyncCodecTest {

    private val origin = "phone-primary"
    private val sid = "6f1c0a1e4b2d4f8ea1c3d5e7f9012345"
    private val hex64a = "a".repeat(64)
    private val hex64b = "b".repeat(64)

    private fun ok(result: SyncResult): SyncBody {
        if (result is SyncResult.Err) fail("expected Ok, got ${result.error}")
        return (result as SyncResult.Ok).body
    }

    private fun assertErr(expected: SyncError, result: SyncResult) {
        assertTrue("expected Err($expected), got $result", result is SyncResult.Err)
        assertEquals(expected, (result as SyncResult.Err).error)
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun payloadBody(chars: Int): String = PayloadCodec.encode(
        RelayPayload(
            id = "123e4567-e89b-42d3-a456-426614174000",
            ts = 1_700_000_000_000L,
            body = "x".repeat(chars),
        ),
    )

    private fun sent(chars: Int) = SyncBody.Sent(origin, sid, 7, hex64b, b64(payloadBody(chars)))

    // --- round trips -------------------------------------------------------------------------

    @Test
    fun everyKindRoundTrips() {
        val bodies = listOf(
            sent(10),
            SyncBody.Read(origin, sid, 1, "dm:$hex64b", 1_700_000_000_000L),
            SyncBody.Keys(origin, sid, 3, b64("current-keyset")),
            SyncBody.Contact(origin, sid, 4, b64("""{"name":"Ann"}""")),
            SyncBody.ContactDelete(origin, sid, 5, b64(hex64a)),
            SyncBody.Group(origin, sid, 6, b64("""{"id":"g"}""")),
            SyncBody.Settings(origin, sid, 8, b64("""{"typing":false}""")),
            SyncBody.Delete(origin, sid, 9, "dm:$hex64b", "123e4567-e89b-42d3-a456-426614174000"),
            SyncBody.Sent(origin, sid, 10, "group:$hex64a:44444444-4444-4444-8444-444444444444", b64(payloadBody(5))),
        )
        for (body in bodies) {
            val wire = SyncCodec.encode(body)
            assertTrue("prefix: $wire", wire.startsWith("TYPE:SYNC|"))
            assertTrue("version: $wire", wire.contains("|V:1|"))
            assertTrue("sync id: $wire", wire.contains("SID:$sid"))
            assertTrue("origin: $wire", wire.contains("O:$origin"))
            assertEquals(body, ok(SyncCodec.decode(wire)))
        }
    }

    @Test
    fun wireFormatIsCompactAndFieldOrderIsStable() {
        assertEquals(
            "TYPE:SYNC|READ|V:1|SID:$sid|R:1|O:phone-primary|C:dm:$hex64b|T:1700000000000",
            SyncCodec.encode(SyncBody.Read(origin, sid, 1, "dm:$hex64b", 1_700_000_000_000L)),
        )
        assertEquals(
            "TYPE:SYNC|DELETE|V:1|SID:$sid|R:9|O:phone-primary|C:dm:$hex64b|ID:123e4567-e89b-42d3-a456-426614174000",
            SyncCodec.encode(SyncBody.Delete(origin, sid, 9, "dm:$hex64b", "123e4567-e89b-42d3-a456-426614174000")),
        )
        val sent = SyncCodec.encode(sent(3))
        assertTrue(sent.contains("|TO:$hex64b|P:"))
        assertTrue(sent.startsWith("TYPE:SYNC|SENT|V:1|SID:"))
        assertTrue(SyncCodec.encode(SyncBody.Keys(origin, sid, 3, "QUJD")).contains("|K:QUJD"))
        assertTrue(SyncCodec.encode(SyncBody.Contact(origin, sid, 4, "QUJD")).contains("|J:QUJD"))
        assertTrue(SyncCodec.encode(SyncBody.Settings(origin, sid, 8, "QUJD")).contains("|J:QUJD"))
    }

    @Test
    fun maximumSizeSentEventStaysUnderThePostBase64EnvelopeBound() {
        // Find the largest SENT payload the encoder accepts, then measure the base64 of the wire
        // body — that is what must fit in the 16 KiB mailbox envelope.
        var lo = 1
        var hi = 40_000
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val fits = runCatching { SyncCodec.encode(sent(mid)) }.isSuccess
            if (fits) lo = mid else hi = mid - 1
        }
        val wire = SyncCodec.encode(sent(lo))
        val encoded = Base64.getEncoder().encodeToString(wire.toByteArray(Charsets.UTF_8))
        println("MAX SENT: text=$lo wire=${wire.toByteArray(Charsets.UTF_8).size} base64=${encoded.length} limit=${SyncCodec.MAX_ENCODED_BYTES}")
        assertTrue("wire ${wire.length} bytes must be <= ${SyncCodec.MAX_RAW_BYTES}", wire.toByteArray(Charsets.UTF_8).size <= SyncCodec.MAX_RAW_BYTES)
        assertTrue("base64 ${encoded.length} must fit 16 KiB", encoded.length <= SyncCodec.MAX_ENCODED_BYTES)
        // The next size up must be refused at encode time, not silently truncated.
        try {
            SyncCodec.encode(sent(lo + 1))
            fail("expected SyncFormatException just over the envelope bound")
        } catch (expected: SyncFormatException) {
            assertTrue(expected.message!!.contains("16 KiB") || expected.message!!.contains("12288"))
        }
    }

    @Test
    fun oversizedBodyIsRefusedAtEncodeTime() {
        val huge = SyncBody.Keys(origin, sid, 3, "A".repeat(SyncCodec.MAX_J_BYTES + 1))
        try {
            SyncCodec.encode(huge)
            fail("expected SyncFormatException")
        } catch (expected: SyncFormatException) {
            assertTrue(expected.message!!.contains("bound") || expected.message!!.contains("16 KiB"))
        }
        // And an unbounded string argument does not blow memory in the decoder either.
        assertErr(SyncError.TOO_LARGE, SyncCodec.decode("TYPE:SYNC|KEYS|V:1|" + "A".repeat(SyncCodec.MAX_LOGICAL_BYTES + 1)))
    }

    @Test
    fun syncIdsAreUniqueAndBounded() {
        val ids = HashSet<String>()
        repeat(1000) { ids += SyncCodec.newSyncId() }
        assertEquals(1000, ids.size)
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{32}")) })
        assertTrue(ids.all { it.length <= SyncCodec.MAX_SYNC_ID })
    }

    // --- decoder rejection -------------------------------------------------------------------

    @Test
    fun unknownVersionAndBadPrefixAreRejected() {
        val wire = SyncCodec.encode(sent(3))
        assertErr(SyncError.UNSUPPORTED_VERSION, SyncCodec.decode(wire.replace("|V:1|", "|V:2|")))
        assertErr(SyncError.BAD_PREFIX, SyncCodec.decode(wire.replace("TYPE:SYNC|", "TYPE:SYNCX|")))
        assertErr(SyncError.BAD_PREFIX, SyncCodec.decode("hello"))
        assertErr(SyncError.BAD_PREFIX, SyncCodec.decode(""))
    }

    @Test
    fun unknownDuplicateAndMissingFieldsAreRejected() {
        val wire = SyncCodec.encode(SyncBody.Read(origin, sid, 1, "dm:$hex64b", 5))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(wire + "|X:1"))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(wire.replace("|R:1|", "|R:1|R:2|")))
        assertErr(SyncError.MALFORMED, SyncCodec.decode("TYPE:SYNC|READ|V:1|SID:$sid|R:1|O:phone-primary"))
        assertErr(SyncError.UNKNOWN_KIND, SyncCodec.decode("TYPE:SYNC|HISTORY|V:1|SID:$sid|R:1|O:phone-primary"))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(wire.replace("SID:$sid", "SID:$sid!")))
        assertErr(SyncError.FIELD_TOO_LARGE, SyncCodec.decode(wire.replace("|R:1|", "|R:-1|")))
    }

    @Test
    fun sentPayloadMustBeAValidRelayPayload() {
        val wire = SyncCodec.encode(sent(3))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(wire.replaceFirst(Regex("\\|P:[^|]+"), "|P:!!!")))
        val notPayload = SyncCodec.encode(sent(3)).replaceFirst(Regex("\\|P:[^|]+"), "|P:" + b64("{}"))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(notPayload))
        // A payload id longer than PayloadCodec allows is refused, not passed through.
        val longId = PayloadCodec.encode(RelayPayload("i".repeat(65), 1, "x"))
        assertErr(SyncError.MALFORMED, SyncCodec.decode(wire.replaceFirst(Regex("\\|P:[^|]+"), "|P:" + b64(longId))))
    }

    @Test
    fun everySyncFieldBoundIsEnforced() {
        fun enc(f: () -> SyncBody) = runCatching { SyncCodec.encode(f()) }.isSuccess
        assertTrue(enc { SyncBody.Read("o".repeat(64), sid, 0, "c".repeat(SyncCodec.MAX_CONVERSATION), 1) })
        assertFalse(enc { SyncBody.Read("o".repeat(65), sid, 0, "c", 1) })
        assertFalse(enc { SyncBody.Read(origin, "s".repeat(65), 0, "c", 1) })
        assertFalse(enc { SyncBody.Read(origin, sid, -1, "c", 1) })
        assertFalse(enc { SyncBody.Read(origin, sid, 0, "c".repeat(SyncCodec.MAX_CONVERSATION + 1), 1) })
        assertTrue(enc { SyncBody.Read(origin, sid, Long.MAX_VALUE, "dm", -1) })
        assertFalse(enc { SyncBody.Sent(origin, sid, 1, "not-a-target", "QUJD") })
        assertFalse(enc { SyncBody.Sent(origin, sid, 1, "group:bad:44444444-4444-4444-8444-444444444444", "QUJD") })
        // At the J field bound the body is accepted logically, but needs chunking for the envelope.
        assertTrue(runCatching { SyncCodec.encodeLogical(SyncBody.Keys(origin, sid, 1, "A".repeat(SyncCodec.MAX_J_BYTES))) }.isSuccess)
        assertFalse(runCatching { SyncCodec.encodeLogical(SyncBody.Keys(origin, sid, 1, "A".repeat(SyncCodec.MAX_J_BYTES + 1))) }.isSuccess)
        assertFalse(enc { SyncBody.Delete(origin, sid, 1, "dm", "not-a-uuid") })
        assertFalse(enc { SyncBody.Contact(origin, sid, 1, "@@@") })
    }

    // --- chunking / PART envelope ------------------------------------------------------------

    @Test
    fun oversizedKeyEventIsSplitIntoBoundedPartsAndReassembles() {
        // Within the field bound but over one envelope: a real keyset can be several KiB.
        val keys = "A".repeat(40_000)
        assertTrue(keys.length <= SyncCodec.MAX_J_BYTES)
        val body = SyncBody.Keys(origin, sid, 3, keys)
        val parts = SyncCodec.chunk(body)
        assertTrue("expected several parts, got ${parts.size}", parts.size > 1)
        assertEquals(parts.size, parts.map { it.substringAfter("|P:").substringBefore('/') }.distinct().size)
        for (wire in parts) {
            assertTrue("part must fit: ${wire.length}", wire.toByteArray(Charsets.UTF_8).size <= SyncCodec.MAX_RAW_BYTES)
            assertTrue(Base64.getEncoder().encodeToString(wire.toByteArray()).length <= SyncCodec.MAX_ENCODED_BYTES)
            val part = ok(SyncCodec.decode(wire)) as SyncBody.Part
            assertEquals("KEYS", part.kind)
            assertEquals(parts.size, part.count)
            assertEquals(origin, part.origin)
            assertEquals(sid, part.syncId)
            assertEquals(3, part.revision)
        }
        println("MAX PART: count=${parts.size} maxWire=${parts.maxOf { it.toByteArray(Charsets.UTF_8).size }} " +
            "maxBase64=${parts.maxOf { Base64.getEncoder().encodeToString(it.toByteArray()).length }}")
        val reassembler = SyncReassembler()
        var complete: String? = null
        for (wire in parts.reversed()) {
            val part = ok(SyncCodec.decode(wire)) as SyncBody.Part
            when (val offer = reassembler.offer(part, now = 1_000L)) {
                is ReassemblyResult.Complete -> complete = offer.body
                else -> Unit
            }
        }
        assertNotNull(complete)
        assertEquals(SyncCodec.encodeLogical(body), complete)
        assertEquals(body, ok(SyncCodec.decode(complete!!)))
    }

    @Test
    fun partEnvelopeCarriesIndexCountTotalAndHash() {
        val content = "hello sync part".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val part = SyncBody.Part(origin, sid, 2, "GROUP", 1, 1, content.size.toLong(), hash,
            Base64.getUrlEncoder().withoutPadding().encodeToString(content))
        val wire = SyncCodec.encode(part)
        assertTrue(wire.startsWith("TYPE:SYNC|GROUP|V:1|SID:$sid|R:2|O:$origin|PART:1|P:1/1|T:${content.size}|H:$hash|D:"))
        assertEquals(part, ok(SyncCodec.decode(wire)))
        // Base64url, no padding: safe inside a `|`-delimited body.
        assertFalse(wire.substringAfter("|D:").contains("="))
        assertFalse(wire.substringAfter("|D:").contains("+"))
        assertFalse(wire.substringAfter("|D:").contains("/"))
    }
}
