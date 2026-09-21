package com.relay.app.pairing

import com.relay.app.util.QrContactCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCoreTest {

    private val npubA = "a".repeat(64)
    private val npubB = "b".repeat(64)
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun pending(deadline: Long = 1_000L, peer: String = npubB, n: String = nonce) = PendingPairing(
        peerPubkeyHex = peer, nonce = n, name = "Bea", publicKeyBase64 = "S0VZ", signingPublicKeyBase64 = "U0lH",
        relayHints = listOf("wss://relay.example"), createdContact = true, contactId = 7, deadlineMs = deadline,
    )

    // ---- sessions -------------------------------------------------------------------------------

    @Test
    fun aCodeCanBeUsedOnce() {
        val s = PairingSessions()
        val n = s.issue()
        assertTrue(s.consume(n))
        assertFalse(s.consume(n))
    }

    @Test
    fun anUnknownCodeIsRejected() {
        assertFalse(PairingSessions().consume(nonce))
    }

    @Test
    fun aCodeExpiresAfterItsTimeToLive() {
        var now = 0L
        val s = PairingSessions(clock = { now })
        val n = s.issue()
        now = PairingRules.NONCE_TTL_MS
        assertTrue("still valid exactly at the limit", s.consume(n))
        val n2 = s.issue()
        now += PairingRules.NONCE_TTL_MS + 1
        assertFalse(s.consume(n2))
    }

    @Test
    fun currentReusesAValidCodeAndIssuesANewOneWhenNoneIsLeft() {
        var now = 0L
        val s = PairingSessions(clock = { now })
        val first = s.current()
        assertEquals(first, s.current())
        s.consume(first)
        val second = s.current()
        assertNotEquals(first, second)
        now += PairingRules.NONCE_TTL_MS + 1
        assertNotEquals(second, s.current())
    }

    @Test
    fun generatedNoncesAreValidAndDifferent() {
        val a = PairingMessages.randomNonce()
        val b = PairingMessages.randomNonce()
        assertTrue(PairingMessages.isValidNonce(a))
        assertNotEquals(a, b)
    }

    // ---- messages -------------------------------------------------------------------------------

    @Test
    fun aRequestRoundTrips() {
        val body = PairingMessages.formatRequest(nonce, "Ana", "S0VZ", "U0lH")
        val parsed = PairingMessages.parse(body) as PairingMessage.Request
        assertEquals(nonce, parsed.nonce)
        assertEquals("Ana", parsed.name)
        assertEquals("S0VZ", parsed.publicKeyBase64)
        assertEquals("U0lH", parsed.signingPublicKeyBase64)
    }

    @Test
    fun aRequestWithoutASigningKeyStillParses() {
        val parsed = PairingMessages.parse(PairingMessages.formatRequest(nonce, "Ana", "S0VZ", null)) as PairingMessage.Request
        assertNull(parsed.signingPublicKeyBase64)
    }

    @Test
    fun aConfirmRoundTrips() {
        val parsed = PairingMessages.parse(PairingMessages.formatConfirm(nonce)) as PairingMessage.Confirm
        assertEquals(nonce, parsed.nonce)
    }

    @Test
    fun namesAreSanitisedAndShortened() {
        val body = PairingMessages.formatRequest(nonce, "A|B\n‮c" + "x".repeat(60), "S0VZ", null)
        val name = (PairingMessages.parse(body) as PairingMessage.Request).name
        assertFalse(name.contains('|') || name.contains('\n') || name.contains('‮'))
        assertTrue(name.length <= 30)
    }

    @Test
    fun malformedOrHostileBodiesAreRejected() {
        val bad = listOf(
            "",
            "hello",
            "TYPE:PUBKEY|KEY:S0VZ",
            "TYPE:PAIR_REQUEST",
            "TYPE:PAIR_REQUEST|NONCE:short|KEY:S0VZ",
            "TYPE:PAIR_REQUEST|NONCE:$nonce",
            "TYPE:PAIR_REQUEST|NONCE:$nonce|KEY:not base64!",
            "TYPE:PAIR_REQUEST|NONCE:$nonce|KEY:S0VZ|SIG:@@@",
            "TYPE:PAIR_CONFIRM|NONCE:${nonce.uppercase()}",
            "TYPE:PAIR_CONFIRM|NONCE",
            "TYPE:PAIR_CONFIRM|" + "x".repeat(10_000),
        )
        for (b in bad) assertNull("should reject: ${b.take(40)}", PairingMessages.parse(b))
    }

    @Test
    fun ordinaryTextAndKeyMessagesAreNotPairingMessages() {
        assertFalse(PairingMessages.isPairingMessage("see you at 5"))
        assertFalse(PairingMessages.isPairingMessage("TYPE:PUBKEY|KEY:S0VZ"))
    }

    // ---- timing ---------------------------------------------------------------------------------

    @Test
    fun theInitiatorWaitsLongerThanTheWindowSoASlowAnswerStillArrives() {
        assertEquals(1_000L + PairingRules.WINDOW_MS + PairingRules.GRACE_MS, PairingRules.outgoingDeadline(1_000L))
        assertTrue(PairingRules.GRACE_MS > 0)
    }

    @Test
    fun theRecipientDeadlineFollowsTheSendersClockWhenPlausible() {
        val received = 1_000_000L
        val sent = received - 20_000L
        assertEquals(sent + PairingRules.WINDOW_MS, PairingRules.incomingDeadline(sent, received))
    }

    @Test
    fun theRecipientIgnoresAnImplausibleSenderTimestamp() {
        val received = 10_000_000L
        assertEquals(received + PairingRules.WINDOW_MS, PairingRules.incomingDeadline(received + 5_000, received)) // future
        assertEquals(received + PairingRules.WINDOW_MS, PairingRules.incomingDeadline(received - PairingRules.WINDOW_MS - 1, received)) // ancient
    }

    @Test
    fun deadlinesAreInclusive() {
        assertFalse(PairingRules.isExpired(100L, 100L))
        assertTrue(PairingRules.isExpired(100L, 101L))
    }

    @Test
    fun aConfirmIsAcceptedOnlyFromTheRightPersonForTheRightCodeInTime() {
        val p = pending(deadline = 1_000L)
        assertTrue(PairingRules.confirmAccepted(p, npubB, nonce, 1_000L))
        assertTrue("case of the key does not matter", PairingRules.confirmAccepted(p, npubB.uppercase(), nonce, 500L))
        assertFalse("someone else", PairingRules.confirmAccepted(p, npubA, nonce, 500L))
        assertFalse("wrong code", PairingRules.confirmAccepted(p, npubB, "f".repeat(32), 500L))
        assertFalse("too late", PairingRules.confirmAccepted(p, npubB, nonce, 1_001L))
        assertFalse("nothing pending", PairingRules.confirmAccepted(null, npubB, nonce, 500L))
    }

    @Test
    fun aRequestFromThePersonWeAreWaitingOnIsACrossRequest() {
        assertTrue(PairingRules.isCrossRequest(pending(), npubB))
        assertFalse(PairingRules.isCrossRequest(pending(), npubA))
        assertFalse(PairingRules.isCrossRequest(null, npubB))
    }

    // ---- pending state persistence --------------------------------------------------------------

    @Test
    fun pendingStateSurvivesSerialisation() {
        val p = pending()
        assertEquals(p, PendingPairing.fromJson(p.toJson()))
        val noSig = p.copy(signingPublicKeyBase64 = null, relayHints = emptyList())
        assertEquals(noSig, PendingPairing.fromJson(noSig.toJson()))
    }

    @Test
    fun corruptPendingStateIsIgnored() {
        assertNull(PendingPairing.fromJson("not json"))
        assertNull(PendingPairing.fromJson("{}"))
    }

    // ---- QR code carries the code ---------------------------------------------------------------

    @Test
    fun aV4CodeCarriesAndReturnsThePairingCode() {
        val raw = QrContactCode.encode("Bea", npubB, "S0VZ", "U0lH", pairNonce = nonce)
        assertTrue(raw.startsWith("RELAYQR:4|"))
        val scanned = QrContactCode.decode(raw)
        assertNotNull(scanned)
        assertEquals(nonce, scanned!!.pairNonce)
        assertEquals(npubB, scanned.nostrPubkeyHex)
    }

    @Test
    fun aCodeWithoutAPairingCodeStaysV3AndHasNoNonce() {
        val raw = QrContactCode.encode("Bea", npubB, "S0VZ")
        assertTrue(raw.startsWith("RELAYQR:3|"))
        assertNull(QrContactCode.decode(raw)!!.pairNonce)
    }

    @Test
    fun aMalformedPairingCodeInTheQrIsDroppedNotTrusted() {
        val raw = "RELAYQR:4|NAME:Bea|NPUB:$npubB|KEY:S0VZ|PAIR:nope"
        val scanned = QrContactCode.decode(raw)
        assertNotNull(scanned)
        assertNull(scanned!!.pairNonce)
    }
}
