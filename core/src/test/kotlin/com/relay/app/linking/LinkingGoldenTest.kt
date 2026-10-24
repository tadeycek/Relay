package com.relay.app.linking

import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Pins the new WP05 codecs in their own corpus (`golden/linking-codecs.golden`), separate from
 * `body-codecs.golden` so no existing codec bytes are touched. Recorded once with
 * `RELAY_GOLDEN_RECORD=1`, then only compared.
 *
 * Randomised material is excluded (HPKE sealing); the bundle's canonical plaintext is pinned here.
 */
class LinkingGoldenTest {

    private val lines = ArrayList<String>()

    private fun esc(s: String): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c.code in 0x20..0x7e -> append(c)
            else -> append("\\u%04x".format(c.code))
        }
    }

    private fun r(x: Any?): String = when (x) {
        null -> "null"
        is LinkResult -> when (x) {
            is LinkResult.Ok -> "Ok(" + r(x.bundle) + ")"
            is LinkResult.Err -> "Err(" + x.error.name + ")"
        }
        is SyncResult -> when (x) {
            is SyncResult.Ok -> "Ok(" + r(x.body) + ")"
            is SyncResult.Err -> "Err(" + x.error.name + ")"
        }
        is LinkBundle -> "Bundle(" + LinkBundleCodec.encode(x) + ")"
        is SyncBody -> SyncCodec.encodeLogical(x)
        is List<*> -> x.joinToString(",", "[", "]") { r(it) }
        is String -> "\"" + x + "\""
        else -> x.toString()
    }

    private fun rec(name: String, block: () -> Any?) {
        val v = try { r(block()) } catch (t: Throwable) { "throws:" + t::class.simpleName }
        lines += "$name\t${esc(v)}"
    }

    private val hex64a = "a".repeat(64)
    private val hex64b = "b".repeat(64)
    private val sid = "6f1c0a1e4b2d4f8ea1c3d5e7f9012345"
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"

    private fun bundle() = LinkBundle(
        createdAt = 1_700_000_000_000L,
        identity = LinkIdentity(
            nostrSecretHex = "11".repeat(32),
            nostrPubkeyHex = hex64a,
            deviceId = "phone-primary",
            deviceLabel = "Pixel",
            hpkeKeysetBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
            hpkePreviousKeysetBase64 = null,
            hpkeKeysetExpiresAt = 1_700_086_400_000L,
        ),
        contacts = listOf(
            LinkContact(
                name = "Ann", phone = "+386 40 123 456", hasRelay = true, trustLevel = "trusted",
                publicKey = Base64.getEncoder().encodeToString(ByteArray(44) { (it * 3).toByte() }),
                nostrPubkey = hex64b, relayHints = listOf("wss://relay.example"), qrVerified = true,
            ),
            LinkContact(name = "Bob", trustLevel = "blocked"),
        ),
        groups = listOf(
            LinkGroup("44444444-4444-4444-8444-444444444444", "Team", hex64a, listOf(hex64a, hex64b)),
        ),
        settings = LinkSettings(typingIndicators = false, retentionDays = 7, theme = "dark", locale = "sl-SI"),
    )

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun sent(chars: Int) = SyncBody.Sent(origin = "phone-primary", syncId = sid, revision = 7,
        target = hex64b, payloadBase64 = b64(PayloadCodec.encode(RelayPayload(uuid, 1_700_000_000_000L, "x".repeat(chars)))))

    private fun corpus() {
        val code = LinkCode.fromBytes(ByteArray(32) { it.toByte() })
        rec("link.code.canonical") { code }
        rec("link.code.human") { LinkCode.human(code) }
        rec("link.code.qr") { LinkCode.qr(code) }
        rec("link.code.slot") { LinkCode.slotId(code) }
        rec("link.code.proof") { LinkCode.proofValue(code) }
        rec("link.code.canonical.human") { LinkCode.canonical(LinkCode.human(code)) }
        rec("link.code.invalid.short") { LinkCode.canonical(code.drop(1)) }

        val b = bundle()
        rec("bundle.encode") { b }
        rec("bundle.decode") { LinkBundleCodec.decode(LinkBundleCodec.encode(b)) }
        rec("bundle.decode.history") {
            LinkBundleCodec.decode(LinkBundleCodec.encode(b).replaceFirst("{\"v\":1", "{\"v\":1,\"messages\":[]"))
        }
        rec("bundle.decode.v2") { LinkBundleCodec.decode(LinkBundleCodec.encode(b).replace("\"v\":1", "\"v\":2")) }
        rec("bundle.name.atLimit") {
            LinkBundleCodec.encode(b.copy(contacts = listOf(LinkContact(name = "n".repeat(LinkBundleCodec.MAX_NAME)))))
        }
        rec("bundle.name.overLimit") {
            LinkBundleCodec.encode(b.copy(contacts = listOf(LinkContact(name = "n".repeat(LinkBundleCodec.MAX_NAME + 1)))))
        }
        rec("bundle.contacts.overLimit") {
            LinkBundleCodec.encode(b.copy(contacts = List(LinkBundleCodec.MAX_CONTACTS + 1) { LinkContact(name = "n") }))
        }

        val bodies = listOf(
            sent(10),
            SyncBody.Read("phone-primary", sid, 1, "dm:$hex64b", 1_700_000_000_000L),
            SyncBody.Keys("phone-primary", sid, 3, b64("current-keyset")),
            SyncBody.Contact("phone-primary", sid, 4, b64("""{"name":"Ann"}""")),
            SyncBody.ContactDelete("phone-primary", sid, 5, b64(hex64a)),
            SyncBody.Group("phone-primary", sid, 6, b64("""{"id":"g"}""")),
            SyncBody.Settings("phone-primary", sid, 8, b64("""{"typing":false}""")),
            SyncBody.Delete("phone-primary", sid, 9, "dm:$hex64b", uuid),
        )
        bodies.forEachIndexed { i, body ->
            rec("sync.encode.$i") { body }
            rec("sync.decode.$i") { SyncCodec.decode(SyncCodec.encodeLogical(body)) }
        }
        rec("sync.decode.v2") { SyncCodec.decode(SyncCodec.encodeLogical(bodies[1]).replace("|V:1|", "|V:2|")) }
        rec("sync.decode.unknownKind") { SyncCodec.decode("TYPE:SYNC|HISTORY|V:1|SID:$sid|R:1|O:phone-primary") }
        rec("sync.decode.extraField") { SyncCodec.decode(SyncCodec.encodeLogical(bodies[1]) + "|X:1") }
        rec("sync.decode.badSentPayload") {
            SyncCodec.decode(SyncCodec.encodeLogical(bodies[0]).replaceFirst(Regex("\\|P:[^|]+"), "|P:!!!"))
        }
        val parts = SyncCodec.chunk(SyncBody.Keys("phone-primary", sid, 3, "A".repeat(40_000)))
        rec("sync.chunk.count") { parts.size }
        parts.forEachIndexed { i, wire -> rec("sync.part.$i") { wire } }
        rec("sync.part.decode") { SyncCodec.decode(parts.first()) }
    }

    @Test
    fun linkingCodecsAreByteIdenticalToTheRecordedCorpus() {
        corpus()
        val actual = lines.joinToString("\n", postfix = "\n")
        val golden = File("src/test/resources/golden/linking-codecs.golden")
        if (System.getenv("RELAY_GOLDEN_RECORD") == "1") {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("GOLDEN RECORDED: ${lines.size} entries -> ${golden.absolutePath}")
            return
        }
        assertEquals("golden entry count", golden.readLines().size, lines.size)
        val expected = golden.readText().split("\n").filter { it.isNotEmpty() }
        lines.forEachIndexed { i, l -> assertEquals("golden entry #$i (${l.substringBefore('\t')})", expected[i], l) }
        assertEquals(golden.readText(), actual)
    }
}
