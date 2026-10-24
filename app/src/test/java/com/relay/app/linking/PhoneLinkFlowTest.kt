package com.relay.app.linking

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The whole decision matrix of linking a computer from the phone, exercised with a fake transport
 * and a fake HPKE: no network, no Android, no clock.
 */
class PhoneLinkFlowTest {

    // --- fixtures -------------------------------------------------------------------------------

    private val code = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val deviceId = "0123456789abcdef0123456789abcdef"
    private val deviceAuthBytes = ByteArray(32) { (it * 7).toByte() }
    private val deviceAuthHex = deviceAuthBytes.joinToString("") { "%02x".format(it) }
    private val ephemeralBytes = ByteArray(64) { (it * 3 + 1).toByte() }

    private val qr = LinkQr.encode(
        code,
        deviceId,
        base64Url(ephemeralBytes),
        base64Url(deviceAuthBytes),
    )

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val bundle = LinkBundle(
        createdAt = 1_700_000_000_000,
        identity = LinkIdentity(
            nostrSecretHex = "11".repeat(32),
            nostrPubkeyHex = "22".repeat(32),
            deviceId = "primary",
            deviceLabel = "Tadej's phone",
            hpkeKeysetBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 5 }),
        ),
        contacts = listOf(
            LinkContact(name = "Alice", nostrPubkey = "33".repeat(32), trustLevel = "trusted", qrVerified = true),
            LinkContact(name = "Bob", phone = "+441234567890", trustLevel = "ask"),
        ),
        groups = listOf(LinkGroup(id = "8b6b6f5e-1111-4222-8333-444455556666", name = "Walkers", members = listOf("33".repeat(32)))),
        settings = LinkSettings(readReceipts = false, typingIndicators = true, retentionDays = 7, theme = "dark"),
    )

    private class FakeHpke : LinkHpke {
        var plaintext: ByteArray? = null
        var recipient: String? = null
        var fail = false
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? {
            if (fail) return null
            recipient = recipientPublicKeyBase64
            this.plaintext = plaintext
            return plaintext
        }

        override fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray): ByteArray? =
            throw UnsupportedOperationException("the phone never opens link bundles")
    }

    private class FakeTransport : PhoneLinkTransport {
        val calls = mutableListOf<String>()
        var publishResult = LinkHttp(200)
        var enrolResult = LinkHttp(200)
        var discardResult = LinkHttp(200)
        var publishedSlot: String? = null
        var publishedBytes: ByteArray? = null
        var enrolledDevice: String? = null
        var enrolledKey: String? = null
        var enrolledLabel: String? = null
        var discardedSlot: String? = null
        var discardedProof: String? = null

        override suspend fun publish(slot: String, sealed: ByteArray): LinkHttp {
            calls += "publish"
            publishedSlot = slot
            publishedBytes = sealed
            return publishResult
        }

        override suspend fun discard(slot: String, proof: String): LinkHttp {
            calls += "discard"
            discardedSlot = slot
            discardedProof = proof
            return discardResult
        }

        override suspend fun enrol(deviceId: String, authPubkeyHex: String, label: String): LinkHttp {
            calls += "enrol"
            enrolledDevice = deviceId
            enrolledKey = authPubkeyHex
            enrolledLabel = label
            return enrolResult
        }
    }

    private fun flow(transport: PhoneLinkTransport, hpke: LinkHpke = FakeHpke()) =
        PhoneLinkFlow(transport, hpke, label = "Computer")

    // --- success --------------------------------------------------------------------------------

    @Test
    fun successPublishesToTheScannedSlotThenEnrolsTheDesktopKey() = runBlocking {
        val transport = FakeTransport()
        val result = flow(transport).link(qr) { bundle }

        assertEquals(PhoneLinkResult.Linked(deviceId, LinkCode.slotId(code), transport.publishedBytes!!.size), result)
        assertEquals(listOf("publish", "enrol"), transport.calls)
        assertEquals(LinkCode.slotId(code), transport.publishedSlot)
        assertEquals(deviceId, transport.enrolledDevice)
        // The QR carries base64url(32 raw bytes); the server stores the 64-hex Nostr form.
        assertEquals(deviceAuthHex, transport.enrolledKey)
        assertEquals("Computer", transport.enrolledLabel)
        // Success never erases the slot: the desktop still has to fetch it.
        assertNull(transport.discardedSlot)
    }

    @Test
    fun sealedBundleHasTheEnvelopeHeaderAndWasEncryptedToTheScannedEphemeralKey() = runBlocking {
        val transport = FakeTransport()
        val hpke = FakeHpke()
        flow(transport, hpke).link(qr) { bundle }

        val sealed = transport.publishedBytes!!
        assertEquals("RLYB", String(sealed.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(sealed.size > LinkBundleCodec.HEADER_BYTES)
        // The platform HPKE only understands standard base64, so the flow normalises the QR's base64url.
        assertEquals(Base64.getEncoder().encodeToString(ephemeralBytes), hpke.recipient)
    }

    @Test
    fun bundleCarriesContactsGroupsAndSettingsButNeverMessageBodies() = runBlocking {
        val transport = FakeTransport()
        val hpke = FakeHpke()
        flow(transport, hpke).link(qr) { bundle }

        val json = JSONObject(String(hpke.plaintext!!, Charsets.UTF_8))
        val keys = json.keys().asSequence().toSet()
        assertEquals(setOf("v", "createdAt", "identity", "contacts", "groups", "settings"), keys)
        for (forbidden in listOf("messages", "history", "chats", "media", "messageHistory")) {
            assertFalse(forbidden, json.has(forbidden))
        }
        // Not just absent by name: the message text of this identity's chats cannot be there at all,
        // because the model has no field to carry it and the encoder writes only schema fields.
        val raw = String(hpke.plaintext!!, Charsets.UTF_8)
        assertTrue(raw.length < LinkBundleCodec.MAX_PLAINTEXT_BYTES)
        assertEquals("Alice", json.getJSONArray("contacts").getJSONObject(0).getString("name"))
        assertEquals("Walkers", json.getJSONArray("groups").getJSONObject(0).getString("name"))
        assertEquals("dark", json.getJSONObject("settings").getString("theme"))
        // A round-trip through the decoder proves the phone produced a bundle the desktop can open.
        val opened = LinkBundleCodec.decode(raw)
        assertTrue(opened is LinkResult.Ok)
        assertEquals(bundle.contacts.map { it.name }, (opened as LinkResult.Ok).bundle.contacts.map { it.name })
    }

    // --- refused scans --------------------------------------------------------------------------

    @Test
    fun legacyQrIsRefusedWithAnUpdateMessageAndTouchesNothing() = runBlocking {
        val transport = FakeTransport()
        val result = flow(transport).link(LinkCode.qr(code)) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.LEGACY_CODE, result.error)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("Update Relay on the computer"))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun malformedQrIsRefusedAndTouchesNothing() = runBlocking {
        val transport = FakeTransport()
        val damaged = qr.replace(deviceId, "0123456789abcde")
        val result = flow(transport).link(damaged) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.MALFORMED_CODE, result.error)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun ordinaryTextIsNotALinkCode() = runBlocking {
        val transport = FakeTransport()
        val result = flow(transport).link("https://example.com") { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.NOT_A_LINK_CODE, result.error)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun blankScanIsRefused() = runBlocking {
        val transport = FakeTransport()
        assertEquals(PhoneLinkError.NOT_A_LINK_CODE, (flow(transport).link("  ") { bundle } as PhoneLinkResult.Failed).error)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun deviceKeyThatIsNot32BytesIsRefusedBeforeUploading() = runBlocking {
        val transport = FakeTransport()
        val shortKey = LinkQr.encode(code, deviceId, base64Url(ephemeralBytes), base64Url(ByteArray(16)))
        val result = flow(transport).link(shortKey) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.BAD_DEVICE_KEY, result.error)
        assertTrue(transport.calls.isEmpty())
    }

    // --- server refusals on the slot ------------------------------------------------------------

    @Test
    fun busySlotIsRefusedWithoutEnrollingOrDiscarding() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(409, "link_busy") }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.SLOT_BUSY, result.error)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("ten minutes"))
        assertEquals(listOf("publish"), transport.calls)
    }

    @Test
    fun expiredSlotIsReportedForBoth404And410() = runBlocking {
        for (status in listOf(404, 410)) {
            val transport = FakeTransport().apply { publishResult = LinkHttp(status, "not_found") }
            val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed
            assertEquals("HTTP $status", PhoneLinkError.SLOT_EXPIRED, result.error)
            assertEquals("HTTP $status", listOf("publish"), transport.calls)
        }
    }

    @Test
    fun refusedKeyIsAuthInvalid() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(401, "auth_invalid") }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.AUTH_INVALID, result.error)
        assertEquals(listOf("publish"), transport.calls)
    }

    @Test
    fun rateLimitedIsRetryable() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(429, "rate_limited") }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.RATE_LIMITED, result.error)
        assertTrue(result.retryable)
    }

    @Test
    fun unreachableServerOnPublishIsNetworkAndNothingIsEnrolled() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(0) }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.NETWORK, result.error)
        assertTrue(result.retryable)
        assertEquals(listOf("publish"), transport.calls)
    }

    @Test
    fun serverErrorIsRetryableOnlyFor5xx() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(500, null) }
        assertTrue((flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed).retryable)

        val rejected = FakeTransport().apply { publishResult = LinkHttp(400, "bad_request") }
        val result = flow(rejected).link(qr) { bundle } as PhoneLinkResult.Failed
        assertEquals(PhoneLinkError.SERVER_ERROR, result.error)
        assertFalse(result.retryable)
    }

    // --- bundle problems ------------------------------------------------------------------------

    @Test
    fun bundleOverTheSlotCapIsRefusedBeforeUploading() = runBlocking {
        val transport = FakeTransport()
        val huge = bundle.copy(
            contacts = (1..LinkBundleCodec.MAX_CONTACTS).map {
                LinkContact(name = "C".repeat(LinkBundleCodec.MAX_NAME), phone = "9".repeat(LinkBundleCodec.MAX_PHONE))
            },
        )
        val result = flow(transport).link(qr) { huge } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.BUNDLE_TOO_LARGE, result.error)
        assertFalse(result.retryable)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun hpkeThatCannotSealIsReportedAsSealFailed() = runBlocking {
        val transport = FakeTransport()
        val result = flow(transport, FakeHpke().apply { fail = true }).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.SEAL_FAILED, result.error)
        assertTrue(transport.calls.isEmpty())
    }

    // --- enrolment failures roll the slot back --------------------------------------------------

    @Test
    fun enrolmentFailureErasesTheSlotWithTheProof() = runBlocking {
        val transport = FakeTransport().apply { enrolResult = LinkHttp(409, "device_limit") }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.DEVICE_LIMIT, result.error)
        assertFalse(result.slotLeftBehind)
        assertEquals(listOf("publish", "enrol", "discard"), transport.calls)
        assertEquals(LinkCode.slotId(code), transport.discardedSlot)
        assertEquals(code, transport.discardedProof)
        assertTrue(result.message.contains("three linked computers"))
    }

    @Test
    fun authKeyInUseAlsoRollsBack() = runBlocking {
        val transport = FakeTransport().apply { enrolResult = LinkHttp(409, "auth_key_in_use") }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.AUTH_KEY_IN_USE, result.error)
        assertEquals(listOf("publish", "enrol", "discard"), transport.calls)
    }

    @Test
    fun enrolmentNetworkFailureRollsBackAndIsRetryable() = runBlocking {
        val transport = FakeTransport().apply { enrolResult = LinkHttp(0) }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.NETWORK, result.error)
        assertTrue(result.retryable)
        assertFalse(result.slotLeftBehind)
        assertEquals(listOf("publish", "enrol", "discard"), transport.calls)
    }

    @Test
    fun failedRollbackIsReportedWithTheTenMinuteExpiry() = runBlocking {
        val transport = FakeTransport().apply {
            enrolResult = LinkHttp(500)
            discardResult = LinkHttp(404, "not_found")
        }
        val result = flow(transport).link(qr) { bundle } as PhoneLinkResult.Failed

        assertEquals(PhoneLinkError.ENROLMENT_REFUSED, result.error)
        assertTrue(result.retryable)
        assertTrue(result.slotLeftBehind)
        assertTrue(result.message.contains("ten minutes"))
    }

    // --- concurrency ----------------------------------------------------------------------------

    @Test
    fun secondAttemptWhileOneIsRunningIsRefusedWithoutANetworkCall() = runBlocking {
        lateinit var flow: PhoneLinkFlow
        var nested: PhoneLinkResult? = null
        var publishes = 0
        val transport = object : PhoneLinkTransport {
            override suspend fun publish(slot: String, sealed: ByteArray): LinkHttp {
                publishes++
                // Re-enter the flow exactly as a second tap would, while the publish is suspended here.
                nested = flow.link(qr) { bundle }
                return LinkHttp(200)
            }

            override suspend fun discard(slot: String, proof: String) = LinkHttp(200)
            override suspend fun enrol(deviceId: String, authPubkeyHex: String, label: String) = LinkHttp(200)
        }
        flow = PhoneLinkFlow(transport, FakeHpke())

        val first = flow.link(qr) { bundle }
        assertTrue(first is PhoneLinkResult.Linked)
        val second = nested as PhoneLinkResult.Failed
        assertEquals(PhoneLinkError.ALREADY_LINKING, second.error)
        assertEquals(1, publishes)
    }

    @Test
    fun aRefusedAttemptDoesNotPoisonTheNextOne() = runBlocking {
        val transport = FakeTransport().apply { publishResult = LinkHttp(0) }
        val target = flow(transport)
        assertNotNull(target.link(qr) { bundle })
        transport.publishResult = LinkHttp(200)
        assertTrue(target.link(qr) { bundle } is PhoneLinkResult.Linked)
    }
}
