package com.relay.app.desktop

import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkCode
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkGroup
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkQr
import com.relay.app.linking.LinkSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rust.nostr.sdk.Keys
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * WP05 part B, desktop half, without a network or a display: a fake [LinkHttp], a fake clock and the
 * real sealed-bundle codec / Tink HPKE. Every user-visible branch of [DesktopLinkFlow] is covered
 * here, including the failure mapping and the "failed commit leaves the slot unconsumed" rule.
 */
class DesktopLinkFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- fakes ---------------------------------------------------------------------------------

    private class FakeClock(var now: Long = 1_000_000L) : LinkClock {
        var sleeps = 0
        override fun now(): Long = now
        override suspend fun sleep(ms: Long) {
            sleeps++
            now += ms
        }
    }

    private class FakeHttp : LinkHttp {
        var bundle: ByteArray? = null
        var refusal: LinkHttp.Response.Refused? = null
        var unreachable = false
        var deleteResponse: LinkHttp.Response = LinkHttp.Response.Body("""{"v":1,"consumed":true}""".toByteArray())
        val gets = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<Pair<String, String>>()

        override suspend fun get(slot: String, proof: String): LinkHttp.Response {
            gets += slot to proof
            if (unreachable) return LinkHttp.Response.Unreachable("test")
            refusal?.let { return it }
            bundle?.let { return LinkHttp.Response.Body(it) }
            return LinkHttp.Response.Refused(404, "not_found")
        }

        override suspend fun delete(slot: String, proof: String): LinkHttp.Response {
            deletes += slot to proof
            return deleteResponse
        }
    }

    private class FakeImporter : LinkImporter {
        var calls = 0
        var failures = 0
        var report = LinkImportReport("0".repeat(64), contactsInserted = 2, contactsUpdated = 0, groups = 1, settingsApplied = true)

        override fun import(bundle: LinkBundle, device: DesktopLinkDevice): LinkImportReport {
            calls++
            if (failures > 0) {
                failures--
                throw IllegalStateException("disk full at /home/someone/secret/link.json")
            }
            return LinkImportReport(
                identityPubkeyHex = bundle.identity.nostrPubkeyHex,
                contactsInserted = report.contactsInserted,
                contactsUpdated = report.contactsUpdated,
                groups = report.groups,
                settingsApplied = report.settingsApplied,
            )
        }
    }

    private class Harness(val paths: DesktopPaths, val secrets: FileSecretStore, val linkKeys: DesktopLinkKeys) {
        val provider = object : LinkKeyProvider {
            override fun loadOrCreate(deviceLabel: String?): DesktopLinkSession =
                linkKeys.loadOrCreate(deviceLabel ?: "Test Computer")
        }

        fun session(): DesktopLinkSession = provider.loadOrCreate(null)

        fun hpke(session: DesktopLinkSession = session()) = DesktopLinkHpke { session.ephemeralPrivateKeysetJson }
    }

    private fun harness(): Harness {
        val config = tmp.newFolder("config-" + UUID.randomUUID()).toPath()
        val data = tmp.newFolder("data-" + UUID.randomUUID()).toPath()
        val paths = DesktopPaths(config, data)
        val secrets = FileSecretStore(paths.secretFile)
        return Harness(paths, secrets, DesktopLinkKeys(paths, secrets))
    }

    private fun flow(
        http: LinkHttp,
        clock: LinkClock,
        harness: Harness,
        importer: LinkImporter,
        code: String,
    ) = DesktopLinkFlow(
        http = http,
        hpke = harness.hpke(),
        keys = harness.provider,
        importer = importer,
        clock = clock,
        pollIntervalMs = 1_000L,
        codeSource = { code },
    )

    // ---- bundle fixture ------------------------------------------------------------------------

    private fun phoneKeyset(): Pair<String, KeysetHandle> {
        com.google.crypto.tink.config.TinkConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get(DesktopKeyMaterial.TEMPLATE))
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(out))
        return Base64.getEncoder().encodeToString(out.toByteArray()) to handle
    }

    private fun bundle(
        keys: Keys = Keys.generate(),
        createdAt: Long = 5_000L,
        settings: LinkSettings = LinkSettings(retentionDays = 3, theme = "dark", readReceipts = false),
    ): Pair<LinkBundle, Keys> {
        val (keysetJson, _) = phoneKeyset()
        val member = Keys.generate().publicKey().toHex()
        return LinkBundle(
            createdAt = createdAt,
            identity = LinkIdentity(
                nostrSecretHex = keys.secretKey().toHex(),
                nostrPubkeyHex = keys.publicKey().toHex(),
                deviceId = "primary",
                deviceLabel = "Pixel 9",
                hpkeKeysetBase64 = keysetJson,
            ),
            contacts = listOf(
                LinkContact(name = "Ana", phone = "+441234", hasRelay = true, trustLevel = "trusted",
                    nostrPubkey = Keys.generate().publicKey().toHex(), qrVerified = true),
                LinkContact(name = "Bo", phone = "+445678", hasRelay = false, trustLevel = "ask"),
            ),
            groups = listOf(
                LinkGroup(id = UUID.randomUUID().toString(), name = "Flat", sharedOwner = null, members = listOf(member)),
            ),
            settings = settings,
        ) to keys
    }

    private fun seal(harness: Harness, bundle: LinkBundle, session: DesktopLinkSession = harness.session()): ByteArray =
        LinkBundleCodec.seal(harness.hpke(session), session.ephemeralPublicKeyBase64Url, bundle)

    /** Seals arbitrary JSON, for bundles `LinkBundleCodec.seal` would refuse to produce. */
    private fun sealJson(harness: Harness, json: String, session: DesktopLinkSession = harness.session()): ByteArray {
        val ciphertext = harness.hpke(session).encryptTo(session.ephemeralPublicKeyBase64Url, json.toByteArray())!!
        val out = ByteArray(LinkBundleCodec.HEADER_BYTES + ciphertext.size)
        "RLYB".toByteArray().copyInto(out, 0)
        out[4] = 1
        out[5] = 1
        val n = ciphertext.size
        out[6] = (n ushr 24).toByte()
        out[7] = (n ushr 16).toByte()
        out[8] = (n ushr 8).toByte()
        out[9] = n.toByte()
        ciphertext.copyInto(out, LinkBundleCodec.HEADER_BYTES)
        return out
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    fun `shows a linkable QR for a 16 hex device id and a 64 hex code`() {
        val harness = harness()
        val flow = flow(FakeHttp(), FakeClock(), harness, FakeImporter(), code = "ab".repeat(32))

        val showing = flow.begin() as LinkState.Showing
        val qr = LinkQr.decode(flow.qrText)
        assertNotNull("QR must decode as a Relay link QR", qr)
        assertTrue(flow.qrText!!.startsWith("RELAYLINK:2:"))
        assertEquals("ab".repeat(32), qr!!.code)
        assertEquals(32, qr.deviceId!!.length)
        assertEquals(32, showing.deviceId.length)
        assertTrue(qr.isLinkable)
        assertEquals(harness.session().ephemeralPublicKeyBase64Url, qr.ephemeralPublicKey)
        assertEquals(harness.session().deviceAuthPublicKeyHex, qr.deviceAuthPublicKey)
        assertEquals(8, flow.humanCode!!.split('-').size)
        // begin() twice renders the same QR: the keys and code are stable.
        assertEquals(flow.qrText, flow.begin().let { flow.qrText })
    }

    @Test
    fun `successful link imports, persists and consumes the slot after the commit`() = runBlocking {
        val harness = harness()
        val http = FakeHttp()
        val importer = FakeImporter()
        val clock = FakeClock()
        val code = "11".repeat(32)
        val (bundle, _) = bundle()
        http.bundle = seal(harness, bundle)
        val flow = flow(http, clock, harness, importer, code)

        flow.begin()
        val state = flow.step()

        assertTrue("expected Linked, got $state", state is LinkState.Linked)
        val linked = state as LinkState.Linked
        assertEquals(bundle.identity.nostrPubkeyHex, linked.identityPubkeyHex)
        assertEquals("Pixel 9", linked.identityLabel)
        assertEquals(harness.session().deviceId, linked.deviceId)
        assertEquals(2, linked.contactsInserted)
        assertTrue(linked.slotCompleted)
        assertTrue(flow.committed)
        assertEquals(1, importer.calls)

        // The GET carried the proof header value and the sha256 slot; DELETE used the same pair.
        val slot = LinkCode.slotId(code)
        assertEquals(listOf(slot to code), http.gets)
        assertEquals(listOf(slot to code), http.deletes)
    }

    @Test
    fun `expired slot ends in Expired and never deletes`() = runBlocking {
        val harness = harness()
        val http = FakeHttp() // 404: the phone never published
        val importer = FakeImporter()
        val clock = FakeClock()
        val flow = flow(http, clock, harness, importer, "22".repeat(32))

        flow.begin()
        clock.now += DesktopLinkFlow.DEFAULT_TTL_MS
        val state = flow.step()

        assertTrue("expected Expired, got $state", state is LinkState.Expired)
        assertEquals(0, importer.calls)
        assertTrue(http.deletes.isEmpty())
        assertFalse(flow.committed)
    }

    @Test
    fun `waiting polls until the TTL and then reports expiry`() = runBlocking {
        val harness = harness()
        val http = FakeHttp()
        val clock = FakeClock()
        val flow = flow(http, clock, harness, FakeImporter(), "33".repeat(32))

        // await() drives begin() itself: the first GET happens at t=0 and the last at t=599 s.
        val state = flow.await()
        assertTrue("expected Expired, got $state", state is LinkState.Expired)
        assertEquals(600, http.gets.size) // 10 minutes / 1 s poll interval
    }

    @Test
    fun `wrong proof is reported as a wrong code and never imports`() = runBlocking {
        val harness = harness()
        val http = FakeHttp().apply { refusal = LinkHttp.Response.Refused(401, "auth_invalid") }
        val importer = FakeImporter()
        val flow = flow(http, FakeClock(), harness, importer, "44".repeat(32))

        flow.begin()
        val state = flow.step()

        assertTrue("expected Failed, got $state", state is LinkState.Failed)
        assertEquals(LinkFailure.WRONG_CODE, (state as LinkState.Failed).failure)
        assertEquals(0, importer.calls)
        assertTrue(http.deletes.isEmpty())
    }

    @Test
    fun `rate limiting is its own failure`() = runBlocking {
        val harness = harness()
        val http = FakeHttp().apply { refusal = LinkHttp.Response.Refused(429, "rate_limited") }
        val flow = flow(http, FakeClock(), harness, FakeImporter(), "55".repeat(32))

        flow.begin()
        val state = flow.step() as LinkState.Failed
        assertEquals(LinkFailure.RATE_LIMITED, state.failure)
    }

    @Test
    fun `corrupt envelope is refused before crypto`() = runBlocking {
        val harness = harness()
        val http = FakeHttp().apply { bundle = byteArrayOf('N'.code.toByte(), 'O'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte()) }
        val importer = FakeImporter()
        val flow = flow(http, FakeClock(), harness, importer, "66".repeat(32))

        flow.begin()
        val state = flow.step() as LinkState.Failed
        assertEquals(LinkFailure.CORRUPT_BUNDLE, state.failure)
        assertEquals(0, importer.calls)
        assertTrue(http.deletes.isEmpty())
    }

    @Test
    fun `tampered ciphertext is reported as tampering, not corruption`() = runBlocking {
        val harness = harness()
        val (bundle, _) = bundle()
        val sealed = seal(harness, bundle).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val http = FakeHttp().apply { this.bundle = sealed }
        val flow = flow(http, FakeClock(), harness, FakeImporter(), "77".repeat(32))

        flow.begin()
        val state = flow.step() as LinkState.Failed
        assertEquals(LinkFailure.TAMPERED_BUNDLE, state.failure)
    }

    @Test
    fun `a bundle with history is refused with the history reason`() = runBlocking {
        val harness = harness()
        val (bundle, _) = bundle()
        val plaintext = LinkBundleCodec.encode(bundle).dropLast(1) + ",\"messages\":[{\"body\":\"old\"}]}"
        val http = FakeHttp().apply { this.bundle = sealJson(harness, plaintext) }
        val importer = FakeImporter()
        val flow = flow(http, FakeClock(), harness, importer, "88".repeat(32))

        flow.begin()
        val state = flow.step() as LinkState.Failed
        assertEquals(LinkFailure.HISTORY_PRESENT, state.failure)
        assertEquals(0, importer.calls)
    }

    @Test
    fun `a newer envelope version is a version mismatch`() = runBlocking {
        val harness = harness()
        val (bundle, _) = bundle()
        val sealed = seal(harness, bundle).also { it[4] = 2 }
        val http = FakeHttp().apply { this.bundle = sealed }
        val flow = flow(http, FakeClock(), harness, FakeImporter(), "99".repeat(32))

        flow.begin()
        val state = flow.step() as LinkState.Failed
        assertEquals(LinkFailure.VERSION_MISMATCH, state.failure)
    }

    @Test
    fun `failed commit leaves the slot unconsumed and a retry succeeds`() = runBlocking {
        val harness = harness()
        val http = FakeHttp()
        val importer = FakeImporter().apply { failures = 1 }
        val clock = FakeClock()
        val code = "aa".repeat(32)
        val (bundle, _) = bundle()
        http.bundle = seal(harness, bundle)
        val flow = flow(http, clock, harness, importer, code)

        flow.begin()
        val first = flow.step()
        assertTrue(first is LinkState.Failed)
        assertEquals(LinkFailure.COMMIT_FAILED, (first as LinkState.Failed).failure)
        // The exception text ("disk full at /home/someone/secret/link.json") must not be echoed.
        assertFalse(first.message.contains("/home/someone"))
        assertTrue("no DELETE after a failed commit", http.deletes.isEmpty())
        assertFalse(flow.committed)
        assertTrue("bundle is still at the slot", http.bundle != null)

        val second = flow.step()
        assertTrue("retry should link, got $second", second is LinkState.Linked)
        assertEquals(1, http.deletes.size)
        assertEquals(2, importer.calls)
    }

    @Test
    fun `no private key appears in any failure state`() = runBlocking {
        val harness = harness()
        val session = harness.session()
        val (bundle, _) = bundle()
        val sealedOk = seal(harness, bundle)
        val states = mutableListOf<LinkState>()

        suspend fun runWith(response: LinkHttp.Response): LinkState {
            val http = FakeHttp().also { http ->
                when (response) {
                    is LinkHttp.Response.Body -> http.bundle = response.bytes
                    is LinkHttp.Response.Refused -> http.refusal = response
                    is LinkHttp.Response.Unreachable -> http.unreachable = true
                }
            }
            val flow = flow(http, FakeClock(), harness, FakeImporter(), "bb".repeat(32))
            flow.begin()
            return flow.step()
        }

        states += runWith(LinkHttp.Response.Refused(401, "auth_invalid"))
        states += runWith(LinkHttp.Response.Refused(429, "rate_limited"))
        states += runWith(LinkHttp.Response.Refused(500, "server"))
        states += runWith(LinkHttp.Response.Body(byteArrayOf(1, 2, 3)))
        states += runWith(LinkHttp.Response.Body(sealedOk.copyOf().also { it[it.size - 1] = 0 }))
        states += runWith(LinkHttp.Response.Body(sealJson(harness, "{\"v\":1,\"messages\":[]}")))

        val privateJson = session.ephemeralPrivateKeysetJson
        val authSecret = session.deviceAuthSecretHex
        val privateB64 = Base64.getEncoder().encodeToString(privateJson.toByteArray())
        for (state in states) {
            assertTrue("state must be a failure: $state", state is LinkState.Failed)
            val rendered = state.toString()
            assertFalse("private HPKE keyset leaked", rendered.contains(privateJson) || rendered.contains(privateB64))
            assertFalse("device auth secret leaked", rendered.contains(authSecret))
            assertFalse("nostr secret leaked", rendered.contains(bundle.identity.nostrSecretHex))
        }
    }

    // ---- real importer: idempotency and durable state ----------------------------------------------

    private class RealStack(
        val harness: Harness,
        val identity: DesktopIdentity,
        val keyMaterial: DesktopKeyMaterial,
        val store: DesktopMessageStore,
        val importer: DesktopStoreLinkImporter,
    )

    private fun realStack(clock: LinkClock): RealStack {
        val harness = harness()
        // The flow calls begin() (which creates the keys) before any import; make the fixture do the
        // same so the device id recorded by markLinked is the one the QR would carry.
        harness.session()
        val identity = DesktopIdentity(harness.paths, harness.secrets, linkKeys = harness.linkKeys)
        val keyMaterial = DesktopKeyMaterial(harness.paths, harness.secrets)
        val store = DesktopMessageStore(harness.paths.databaseFile, harness.secrets)
        return RealStack(
            harness,
            identity,
            keyMaterial,
            store,
            DesktopStoreLinkImporter(identity, keyMaterial, store, harness.linkKeys, clock),
        )
    }

    @Test
    fun `import installs keys and contacts and re-import changes nothing`() {
        val stack = realStack(FakeClock())
        val (bundle, phoneKeys) = bundle()

        val first = stack.importer.import(bundle, DesktopLinkDevice("0123456789abcdef", "Test", "c".repeat(64)))
        assertEquals(bundle.identity.nostrPubkeyHex, first.identityPubkeyHex)
        assertEquals(2, first.contactsInserted)
        assertEquals(0, first.contactsUpdated)
        assertEquals(1, first.groups)
        assertTrue(first.settingsApplied)

        // The phone's identity and HPKE keyset are now this install's.
        assertEquals(bundle.identity.nostrPubkeyHex, stack.identity.publicKeyHex())
        assertEquals(phoneKeys.publicKey().toHex(), stack.identity.publicKeyHex())
        assertEquals(2L, stack.store.contactCount())
        assertEquals(1, stack.store.groups().size)
        assertEquals(3, stack.store.linkSettings()!!.retentionDays)
        assertNotNull(stack.harness.linkKeys.linkedDevice())
        assertFalse(stack.identity.isLocalOnly())

        // A second import of the same bundle is a no-op.
        val second = stack.importer.import(bundle, DesktopLinkDevice("0123456789abcdef", "Test", "c".repeat(64)))
        assertEquals(0, second.contactsInserted)
        assertEquals(0, second.contactsUpdated)
        assertEquals(0, second.groups)
        assertFalse(second.settingsApplied)
        assertEquals(2L, stack.store.contactCount())
        assertEquals(1, stack.store.groups().size)

        // Restart: a fresh store/keys load sees the same link and the same rows.
        stack.store.close()
        val reopened = DesktopMessageStore(stack.harness.paths.databaseFile, stack.harness.secrets)
        reopened.use {
            assertEquals(2L, it.contactCount())
            assertEquals("Ana", it.contacts().first().name)
            assertEquals(3, it.linkSettings()!!.retentionDays)
        }
        val reopenedKeys = DesktopLinkKeys(stack.harness.paths, stack.harness.secrets)
        assertEquals(bundle.identity.nostrPubkeyHex, reopenedKeys.linkedDevice()!!.identityPubkeyHex)
        assertEquals(32, reopenedKeys.linkedDevice()!!.deviceId.length)
    }

    @Test
    fun `re-import never overwrites newer local settings`() {
        val stack = realStack(FakeClock())
        val phone = Keys.generate()
        val (bundle, _) = bundle(keys = phone, createdAt = 10_000L)
        stack.importer.import(bundle, DesktopLinkDevice("0123456789abcdef", "Test", "d".repeat(64)))
        assertEquals(3, stack.store.linkSettings()!!.retentionDays)

        // A local change "after" the imported bundle.
        stack.store.setMeta("link.settings.updatedAt", "20000")
        stack.store.setMeta(
            "link.settings",
            """{"typingIndicators":false,"notificationPreview":false,"retentionDays":30,""" +
                """"readReceipts":true,"mediaAutoDownload":false,"theme":"light","locale":"sl"}""",
        )

        val older = bundle(keys = phone, createdAt = 15_000L).first
        val report = stack.importer.import(older, DesktopLinkDevice("0123456789abcdef", "Test", "d".repeat(64)))
        assertFalse("older settings must not be applied", report.settingsApplied)
        assertEquals(30, stack.store.linkSettings()!!.retentionDays)
        assertEquals("light", stack.store.linkSettings()!!.theme)
        assertEquals(20_000L, stack.store.linkSettingsUpdatedAt())
    }

    @Test
    fun `import refuses a bundle whose secret does not match its public key`() {
        val stack = realStack(FakeClock())
        val (bundle, _) = bundle()
        val tampered = bundle.copy(
            identity = bundle.identity.copy(nostrPubkeyHex = Keys.generate().publicKey().toHex()),
        )
        val error = runCatching {
            stack.importer.import(tampered, DesktopLinkDevice("0123456789abcdef", "Test", "e".repeat(64)))
        }.exceptionOrNull()
        assertTrue("expected a typed refusal, got $error", error is LinkImportException)
        assertEquals(LinkFailure.CORRUPT_BUNDLE, (error as LinkImportException).failure)
        assertNull(stack.harness.linkKeys.linkedDevice())
        assertEquals(0L, stack.store.contactCount())
    }

    @Test
    fun `imported HPKE keyset decrypts a message sealed to the phone key`() {
        val stack = realStack(FakeClock())
        val (keysetJson, phoneKeyset) = phoneKeyset()
        val (bundle, _) = bundle()
        val withKeyset = bundle.copy(identity = bundle.identity.copy(hpkeKeysetBase64 = keysetJson))
        // A contact seals to the phone's public HPKE key before the desktop exists.
        val publicOut = ByteArrayOutputStream()
        phoneKeyset.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(publicOut))
        val sealed = KeysetHandle.readNoSecret(BinaryKeysetReaderCompat.of(publicOut.toByteArray()))
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt("hello after linking".toByteArray(), ByteArray(0))

        stack.importer.import(withKeyset, DesktopLinkDevice("0123456789abcdef", "Test", "f".repeat(64)))
        val opened = stack.keyMaterial.decryptMine(sealed)
        assertEquals("hello after linking", opened?.toString(Charsets.UTF_8))
    }

    @Test
    fun `link files are 0600 and contain no clear private key`() {
        val stack = realStack(FakeClock())
        val session = stack.harness.session()
        assertTrue(DesktopPaths.isPrivate(stack.harness.paths.linkFile))
        val raw = String(Files.readAllBytes(stack.harness.paths.linkFile), Charsets.ISO_8859_1)
        assertFalse(raw.contains(session.ephemeralPrivateKeysetJson))
        assertFalse(raw.contains(session.deviceAuthSecretHex))
        assertFalse(raw.contains("privateKeyData"))
    }
}

/** Tink 1.24's `BinaryKeysetReader` is the only reader used in production; this keeps the test tidy. */
private object BinaryKeysetReaderCompat {
    fun of(bytes: ByteArray) = com.google.crypto.tink.BinaryKeysetReader.withBytes(bytes)
}
