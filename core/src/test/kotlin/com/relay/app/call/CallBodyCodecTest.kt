package com.relay.app.call

import com.relay.app.call.CT.ALICE
import com.relay.app.call.CT.BOB
import com.relay.app.call.CT.NOW
import com.relay.app.call.CallRejectReason.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Random

class CallBodyCodecTest {
    private fun ok(r: CallParseResult): CallPacket = (r as? CallParseResult.Ok)?.packet ?: fail("expected Ok, got $r").let { error("unreachable") }
    private fun rejected(r: CallParseResult): CallRejectReason = (r as? CallParseResult.Rejected)?.reason ?: fail("expected Rejected, got $r").let { error("unreachable") }
    private fun decodeAsBob(wire: String, sender: String = ALICE, now: Long = NOW + 1000, skew: Long = 10_000, g: NonceReplayGuard? = null) =
        CallBodyCodec.decode(wire, CT.ctx(BOB, sender, now, skew), g)
    private fun rawWire(json: String) = CT.wireOfJson(json)

    // ---- round trip -------------------------------------------------------------------------------------------

    @Test fun everyOpRoundTripsExactly() {
        for (p in CT.everyOp()) {
            val wire = CallBodyCodec.encode(p)
            assertTrue(wire.startsWith("TYPE:CALL|J:"))
            assertTrue("${p.op} wire ${wire.length}", wire.length <= CallLimits.MAX_WIRE_CHARS)
            val back = ok(CallBodyCodec.decode(wire, CT.ctx(CT.receiverOf(p), CT.senderOf(p))))
            assertEquals(p.op.wire, p, back)
        }
    }

    @Test fun isCallRecognisesOnlyThePrefix() {
        assertTrue(CallBodyCodec.isCall("TYPE:CALL|J:abc"))
        assertFalse(CallBodyCodec.isCall("TYPE:FILE|x")); assertFalse(CallBodyCodec.isCall(""))
    }

    @Test fun inviteJsonIsCanonicalAndBindsEveryRequiredField() {
        val j = CT.json(CallBodyCodec.encode(CT.everyOp().first()))
        assertEquals(
            """{"v":1,"op":"invite","call":"${CT.callId(1)}","ts":$NOW,"expires":${NOW + 45_000},"caller":"$ALICE","callee":"$BOB","device":"${CT.A_PHONE}","nonce":"${CT.nonce(1)}","media":"audio"}""",
            j,
        )
    }

    @Test fun sdpTravelsDeflatedAndBase64() {
        val offer = CT.everyOp()[3] as CallPacket.Offer
        val j = CT.json(CallBodyCodec.encode(offer))
        assertFalse("raw SDP must not appear in the JSON", j.contains("v=0"))
        assertTrue(j.contains("\"sdp\":\""))
    }

    @Test fun wp12Ac3RepresentativeOfferAndAnswerStayWithinTheMeasuredEnvelopeBudget() {
        // WP02: a 4,952-char plain body wrapped to a 14,290-byte frame (<= 14 KiB = 14,336). Staying under that body
        // size keeps every packet under the measured frame size and the 16,384-byte server envelope limit.
        val offer = CallBodyCodec.encode(CallPacket.Offer(CT.header(), CT.B_PHONE, 0, CT.SDP))
        val answer = CallBodyCodec.encode(CallPacket.Answer(CT.header(device = CT.B_PHONE), CT.A_PHONE, 0, CT.SDP))
        for (w in listOf(offer, answer)) { assertTrue("wire ${w.length}", w.length <= 4952); assertTrue(w.length <= CallLimits.MAX_WIRE_CHARS) }
        assertTrue(CallLimits.MAX_WIRE_CHARS < 4952)
        assertTrue(CallLimits.MAX_WIRE_CHARS * 3 + 1024 < 16 * 1024 + 4096) // sanity of the documented margin
    }

    // ---- structural hostility -----------------------------------------------------------------------------------

    @Test fun rejectsNonCallAndMalformedEncodings() {
        assertEquals(NOT_CALL_BODY, rejected(decodeAsBob("TYPE:FILE|x")))
        assertEquals(NOT_CALL_BODY, rejected(decodeAsBob("")))
        assertEquals(BAD_ENCODING, rejected(decodeAsBob("TYPE:CALL|J:@@@@")))
        assertEquals(BAD_ENCODING, rejected(decodeAsBob("TYPE:CALL|J:")))
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire("not json"))))
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire("[]"))))
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire(CT.rawJson("invite") + " x"))))
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire(CT.rawJson("invite").replace("\"v\":1", "'v':1")))))
    }

    @Test fun rejectsDuplicateKeys() {
        val dup = CT.rawJson("invite").dropLast(1) + ",\"call\":\"${CT.callId(2)}\"}"
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire(dup))))
    }

    @Test fun rejectsOversizeWireBeforeDecoding() {
        val big = "TYPE:CALL|J:" + "A".repeat(CallLimits.MAX_WIRE_CHARS)
        assertEquals(TOO_LARGE, rejected(decodeAsBob(big)))
        val justOver = rawWire(CT.rawJson("invite", "\"pad\":\"${"x".repeat(3100)}\""))
        assertEquals(TOO_LARGE, rejected(decodeAsBob(justOver)))
    }

    @Test fun rejectsUnknownVersionsOpsAndMedia() {
        for (v in listOf("0", "2", "99", "\"1\"", "1.0", "null", "true")) {
            val r = rejected(decodeAsBob(rawWire(CT.rawJson("invite", v = v))))
            assertTrue("v=$v -> $r", r == UNKNOWN_VERSION || r == BAD_FIELD)
        }
        assertEquals(UNKNOWN_VERSION, rejected(decodeAsBob(rawWire(CT.rawJson("invite", v = "2")))))
        assertEquals(MISSING_FIELD, rejected(decodeAsBob(rawWire(CT.rawJson("invite", drop = setOf("v"))))))
        assertEquals(UNKNOWN_OP, rejected(decodeAsBob(rawWire(CT.rawJson("group_start")))))
        assertEquals(UNKNOWN_OP, rejected(decodeAsBob(rawWire(CT.rawJson("INVITE")))))
        assertEquals(UNSUPPORTED_MEDIA, rejected(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("media" to "\"video\""))))))
    }

    @Test fun rejectsEveryMissingHeaderField() {
        for (f in listOf("op", "call", "ts", "expires", "caller", "callee", "device", "nonce", "media"))
            assertTrue("drop $f", rejected(decodeAsBob(rawWire(CT.rawJson("invite", drop = setOf(f))))) in setOf(MISSING_FIELD, UNKNOWN_OP))
    }

    @Test fun rejectsMalformedHeaderValues() {
        val cases = mapOf(
            "call" to listOf("\"abc\"", "\"${"A".repeat(32)}\"", "123", "null", "\"${"a".repeat(33)}\""),
            "nonce" to listOf("\"zz\"", "\"${"a".repeat(31)}\"", "5"),
            "caller" to listOf("\"abc\"", "\"${"A1".repeat(32)}\"", "[]"),
            "callee" to listOf("\"abc\"", "{}"),
            "device" to listOf("\"\"", "\"PRIMARY\"", "\"${"a".repeat(31)}\"", "\"${"1".repeat(32)} \"", "7"),
            "ts" to listOf("\"1\"", "-1", "1.5", "1e3", "9999999999999999999", "null"),
            "expires" to listOf("\"1\"", "1.5", "true"),
        )
        for ((k, vs) in cases) for (v in vs) {
            val r = rejected(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf(k to v)))))
            assertEquals("$k=$v", BAD_FIELD, r)
        }
    }

    @Test fun acceptsPrimaryAndHexDeviceIds() {
        ok(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("device" to "\"primary\"")))))
        ok(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("device" to "\"${CT.A_PHONE}\"")))))
    }

    @Test fun rejectsUnknownFieldsPerOp() {
        assertEquals(UNKNOWN_FIELD, rejected(decodeAsBob(rawWire(CT.rawJson("invite", "\"x\":1")))))
        assertEquals(UNKNOWN_FIELD, rejected(decodeAsBob(rawWire(CT.rawJson("invite", "\"sdp\":\"AA==\"")))))
    }

    @Test fun directedOpsRequireToAndBroadcastOpsForbidIt() {
        // accept without "to"
        assertEquals(MISSING_FIELD, rejected(CallBodyCodec.decode(rawWire(CT.rawJson("accept", override = mapOf("device" to "\"${CT.B_PHONE}\""))), CT.ctx(ALICE, BOB))))
        // invite with "to"
        assertEquals(UNKNOWN_FIELD, rejected(decodeAsBob(rawWire(CT.rawJson("invite", "\"to\":\"${CT.B_PHONE}\"")))))
        // malformed to
        assertEquals(BAD_FIELD, rejected(CallBodyCodec.decode(rawWire(CT.rawJson("accept", "\"to\":\"nope\"", override = mapOf("device" to "\"${CT.B_PHONE}\""))), CT.ctx(ALICE, BOB))))
    }

    // ---- freshness ------------------------------------------------------------------------------------------------

    @Test fun rejectsExpiredPacketsButToleratesSkew() {
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header())) // expires NOW+45s
        ok(decodeAsBob(wire, now = NOW + 45_000 + 10_000, skew = 10_000)) // exactly at expires + skew
        assertEquals(EXPIRED, rejected(decodeAsBob(wire, now = NOW + 45_000 + 10_001, skew = 10_000)))
        assertEquals(EXPIRED, rejected(decodeAsBob(wire, now = NOW + 45_001, skew = 0)))
        ok(decodeAsBob(wire, now = NOW + 45_000, skew = 0))
    }

    @Test fun rejectsFutureDatedPacketsButToleratesSkew() {
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        ok(decodeAsBob(wire, now = NOW - 10_000, skew = 10_000))
        assertEquals(FUTURE_DATED, rejected(decodeAsBob(wire, now = NOW - 10_001, skew = 10_000)))
        assertEquals(FUTURE_DATED, rejected(decodeAsBob(wire, now = NOW - 1, skew = 0)))
    }

    @Test fun skewToleranceIsAParameterAndNegativeSkewIsRejectedSafely() {
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        ok(decodeAsBob(wire, now = NOW + 60_000, skew = 20_000))
        assertTrue(CallBodyCodec.decode(wire, CT.ctx(BOB, ALICE, NOW, -5)) is CallParseResult.Rejected)
    }

    @Test fun rejectsLifetimesLongerThanTheCapAndNonPositiveLifetimes() {
        val h = CT.header(expires = NOW + CallLimits.MAX_LIFETIME_MS + 1)
        assertEquals(LIFETIME_TOO_LONG, rejected(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("expires" to "${h.expires}"))))))
        ok(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("expires" to "${NOW + CallLimits.MAX_LIFETIME_MS}")))))
        assertEquals(LIFETIME_TOO_LONG, rejected(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("expires" to "$NOW"))))))
        assertEquals(LIFETIME_TOO_LONG, rejected(decodeAsBob(rawWire(CT.rawJson("invite", override = mapOf("expires" to "${NOW - 5}"))))))
    }

    // ---- binding --------------------------------------------------------------------------------------------------

    @Test fun rejectsPacketsWhoseSenderIsNotAPartyOfTheCall() {
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        assertEquals(WRONG_BINDING, rejected(decodeAsBob(wire, sender = CT.CAROL)))
    }

    @Test fun rejectsPacketsWhereTheReceiverIsNotAPartyOfTheCall() {
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(wire, CT.ctx(CT.CAROL, ALICE))))
    }

    @Test fun rejectsSenderClaimedInBodyButNotAuthenticated() {
        // Body says caller=ALICE; transport says the seal was signed by CAROL.
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        assertEquals(WRONG_BINDING, rejected(decodeAsBob(wire, sender = CT.CAROL)))
        // And the other direction: an invite sent by Bob's authenticated identity claiming caller ALICE.
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(wire, CT.ctx(ALICE, BOB))))
    }

    @Test fun rejectsSelfCalls() {
        val j = CT.rawJson("invite", override = mapOf("callee" to "\"$ALICE\""))
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(rawWire(j), CT.ctx(ALICE, ALICE))))
    }

    @Test fun enforcesWhichSideMaySendEachOp() {
        // Only the caller sends invite/cancel/timeout; only the callee sends ringing/accept/decline/busy.
        for (p in CT.everyOp()) {
            val sender = CT.senderOf(p); val receiver = CT.receiverOf(p)
            ok(CallBodyCodec.decode(CallBodyCodec.encode(p), CT.ctx(receiver, sender)))
        }
        val callerOnly = listOf(CallOp.INVITE, CallOp.CANCEL, CallOp.TIMEOUT)
        val calleeOnly = listOf(CallOp.RINGING, CallOp.ACCEPT, CallOp.DECLINE, CallOp.BUSY)
        for (p in CT.everyOp()) {
            // Replay the same packet as if the OTHER identity sent it.
            val otherSender = CT.receiverOf(p); val otherReceiver = CT.senderOf(p)
            val r = CallBodyCodec.decode(CallBodyCodec.encode(p), CT.ctx(otherReceiver, otherSender))
            if (p.op in callerOnly || p.op in calleeOnly) assertEquals("${p.op}", WRONG_BINDING, rejected(r))
        }
    }

    @Test fun selfSignalsAreAcceptedOnlyForAnsweredElsewhereFromTheCallee() {
        val ae = CallPacket.AnsweredElsewhere(CT.header(device = CT.B_PHONE, nonce = CT.nonce(30)), CT.B_PHONE, "answered")
        ok(CallBodyCodec.decode(CallBodyCodec.encode(ae), CT.ctx(BOB, BOB)))
        // The caller cannot loop an answered_elsewhere into its own identity.
        val fromCaller = CallPacket.AnsweredElsewhere(CT.header(nonce = CT.nonce(31)), CT.B_PHONE)
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(CallBodyCodec.encode(fromCaller), CT.ctx(ALICE, ALICE))))
        // No other op is a valid self signal.
        val offer = CallPacket.Offer(CT.header(device = CT.B_PHONE, nonce = CT.nonce(32)), CT.B_DESK, 0, CT.SDP)
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(CallBodyCodec.encode(offer), CT.ctx(BOB, BOB))))
        val decline = CallPacket.Decline(CT.header(device = CT.B_PHONE, nonce = CT.nonce(33)), CT.A_PHONE)
        assertEquals(WRONG_BINDING, rejected(CallBodyCodec.decode(CallBodyCodec.encode(decline), CT.ctx(BOB, BOB))))
    }

    @Test fun answeredElsewhereReasonIsAnsweredOrDeclinedOnly() {
        val j = { r: String -> CT.rawJson("answered_elsewhere", "\"winner\":\"${CT.B_PHONE}\",\"reason\":\"$r\"", override = mapOf("device" to "\"${CT.B_PHONE}\"")) }
        ok(CallBodyCodec.decode(rawWire(j("declined")), CT.ctx(BOB, BOB)))
        assertEquals(BAD_FIELD, rejected(CallBodyCodec.decode(rawWire(j("whatever")), CT.ctx(BOB, BOB))))
    }

    // ---- replay -----------------------------------------------------------------------------------------------------

    @Test fun replayedNonceIsRejectedAfterTheFirstAcceptance() {
        val g = NonceReplayGuard()
        val wire = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        ok(decodeAsBob(wire, g = g))
        assertEquals(REPLAY, rejected(decodeAsBob(wire, g = g)))
    }

    @Test fun sameNonceFromAnotherSenderIsNotAReplay() {
        val g = NonceReplayGuard()
        assertTrue(g.checkAndRecord(ALICE, CT.nonce(1), NOW + 1000, NOW))
        assertTrue(g.checkAndRecord(CT.CAROL, CT.nonce(1), NOW + 1000, NOW))
        assertFalse(g.checkAndRecord(ALICE, CT.nonce(1), NOW + 1000, NOW))
    }

    @Test fun rejectedPacketsDoNotPoisonTheReplayCache() {
        val g = NonceReplayGuard()
        val bad = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        assertEquals(WRONG_BINDING, rejected(decodeAsBob(bad, sender = CT.CAROL, g = g)))
        assertEquals(0, g.size)
        ok(decodeAsBob(bad, g = g)) // the legitimate delivery still works
        assertEquals(1, g.size)
    }

    @Test fun replayGuardMemoryIsBoundedAndForgetsExpiredEntries() {
        // Review finding: evicting the oldest UNEXPIRED entry let an attacker push a live nonce out and then
        // replay its packet. A full guard now refuses new nonces instead; per-sender caps stop one identity
        // filling it.
        // Fill the cache from DIFFERENT senders so the per-sender cap cannot mask the eviction bug.
        fun other(i: Int) = "%064x".format(1000 + i)
        val g = NonceReplayGuard(capacity = 4, perSenderCapacity = 4)
        assertTrue(g.checkAndRecord(ALICE, CT.nonce(0), NOW + 60_000, NOW))
        for (i in 1..3) assertTrue(g.checkAndRecord(other(i), CT.nonce(i), NOW + 60_000, NOW))
        assertEquals(4, g.size)
        assertFalse("a full guard must refuse a new nonce, not evict a live one", g.checkAndRecord(other(9), CT.nonce(9), NOW + 60_000, NOW))
        assertFalse("the victim nonce is still remembered, so its replay is refused", g.checkAndRecord(ALICE, CT.nonce(0), NOW + 60_000, NOW))

        val capped = NonceReplayGuard(capacity = 64, perSenderCapacity = 2)
        assertTrue(capped.checkAndRecord(ALICE, CT.nonce(1), NOW + 60_000, NOW))
        assertTrue(capped.checkAndRecord(ALICE, CT.nonce(2), NOW + 60_000, NOW))
        assertFalse("one sender cannot exceed its own quota", capped.checkAndRecord(ALICE, CT.nonce(3), NOW + 60_000, NOW))
        assertTrue("another sender is unaffected", capped.checkAndRecord(BOB, CT.nonce(1), NOW + 60_000, NOW))
        val g2 = NonceReplayGuard(capacity = 8, perSenderCapacity = 8)
        assertTrue(g2.checkAndRecord(ALICE, CT.nonce(1), NOW + 1_000, NOW))
        assertTrue("expired entry is dropped", g2.checkAndRecord(ALICE, CT.nonce(2), NOW + 99_000, NOW + 50_000))
        assertTrue(g2.size <= 1)
    }

    // ---- SDP bounds ------------------------------------------------------------------------------------------------

    private fun offerJson(sdpB64: String) = CT.rawJson("offer", "\"to\":\"${CT.B_PHONE}\",\"gen\":0,\"sdp\":\"$sdpB64\"")
    private fun decodeOffer(sdpB64: String) = CallBodyCodec.decode(rawWire(offerJson(sdpB64)), CT.ctx(BOB, ALICE))

    @Test fun deflateBombIsRejectedWithoutAllocatingTheExpansion() {
        val wireSized = CT.deflateB64(ByteArray(CallLimits.MAX_SDP_BYTES + 1) { 'a'.code.toByte() })
        assertTrue(wireSized.length < 200)
        assertEquals(SDP_TOO_LARGE, rejected(decodeOffer(wireSized)))
        val hugeBomb = CT.deflateB64(ByteArray(8_000_000)) // ~8 KB on the wire: still refused before inflating
        assertEquals(TOO_LARGE, rejected(decodeAsBob(rawWire(offerJson(hugeBomb)))))
    }

    @Test fun sdpAtExactlyTheInflatedLimitIsAccepted() {
        val raw = "v=0\r\n" + "a=x:".padEnd(CallLimits.MAX_SDP_BYTES - 5, 'b')
        assertEquals(CallLimits.MAX_SDP_BYTES, raw.length)
        val p = ok(decodeOffer(CT.deflateB64(raw.toByteArray()))) as CallPacket.Offer
        assertEquals(raw, p.sdp)
    }

    @Test fun rejectsCorruptTruncatedAndTrailingDeflate() {
        assertEquals(BAD_SDP, rejected(decodeOffer(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))))
        assertEquals(BAD_SDP, rejected(decodeOffer("")))
        val good = Base64.getDecoder().decode(CT.deflateB64(CT.SDP.toByteArray()))
        assertEquals(BAD_SDP, rejected(decodeOffer(Base64.getEncoder().encodeToString(good.copyOf(good.size - 4)))))
        assertEquals(BAD_SDP, rejected(decodeOffer(Base64.getEncoder().encodeToString(good + byteArrayOf(0, 1)))))
        assertEquals(BAD_SDP, rejected(decodeOffer("@@@")))
    }

    @Test fun rejectsSdpThatIsNotTextSdp() {
        for (raw in listOf("hello", "v=0\u0000\r\n", "v=0\r\n\u0007", "x=0\r\n")) assertEquals(raw, BAD_SDP, rejected(decodeOffer(CT.deflateB64(raw.toByteArray()))))
        assertEquals(BAD_SDP, rejected(decodeOffer(CT.deflateB64(byteArrayOf('v'.code.toByte(), '='.code.toByte(), '0'.code.toByte(), 0xC3.toByte())))))
    }

    @Test fun encodeRefusesOversizeOrNonSdp() {
        try { CallBodyCodec.encode(CallPacket.Offer(CT.header(), CT.B_PHONE, 0, "v=0\r\n" + "a".repeat(CallLimits.MAX_SDP_BYTES))); fail() } catch (_: IllegalArgumentException) {}
        try { CallBodyCodec.encode(CallPacket.Offer(CT.header(), CT.B_PHONE, 0, "junk")); fail() } catch (_: IllegalArgumentException) {}
        // Incompressible SDP that inflates within the limit but exceeds the wire cap
        val rnd = Random(7); val noise = "v=0\r\n" + (0 until 6000).map { ('a'.code + rnd.nextInt(26)).toChar() }.joinToString("")
        try { CallBodyCodec.encode(CallPacket.Offer(CT.header(), CT.B_PHONE, 0, noise)); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- candidates -------------------------------------------------------------------------------------------------

    private fun iceJson(cands: String, gen: String = "0") = CT.rawJson("ice", "\"to\":\"${CT.B_PHONE}\",\"gen\":$gen,\"cands\":$cands")
    private fun candJson(c: String, mid: String = "0", idx: String = "0") = """{"mid":"$mid","idx":$idx,"cand":"$c"}"""
    private fun decodeIce(json: String) = CallBodyCodec.decode(rawWire(json), CT.ctx(BOB, ALICE))

    @Test fun acceptsExactlyTwentyCandidatesAndRejectsTwentyOne() {
        val twenty = (1..20).joinToString(",", "[", "]") { candJson("candidate:$it 1 udp 1 192.0.2.1 1 typ host") }
        assertEquals(20, (ok(decodeIce(iceJson(twenty))) as CallPacket.Ice).candidates.size)
        val tooMany = (1..21).joinToString(",", "[", "]") { candJson("candidate:$it 1 udp 1 192.0.2.1 1 typ host") }
        assertEquals(TOO_MANY_CANDIDATES, rejected(decodeIce(iceJson(tooMany))))
        val absurd = (1..500).joinToString(",", "[", "]") { "{}" }
        assertTrue(rejected(decodeIce(iceJson(absurd))) in setOf(TOO_MANY_CANDIDATES, TOO_LARGE, BAD_JSON))
    }

    @Test fun rejectsBadCandidates() {
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("[]"))))
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("{}"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("x".repeat(257)) + "]"))))
        ok(decodeIce(iceJson("[" + candJson("candidate:" + "x".repeat(246)) + "]")))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:" + "x".repeat(247)) + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("not a candidate") + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:1\\u0000") + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:1 \\u00e9") + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:1", mid = "m".repeat(17)) + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:1", idx = "8") + "]"))))
        assertEquals(BAD_CANDIDATE, rejected(decodeIce(iceJson("[" + candJson("candidate:1", idx = "-1") + "]"))))
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("[" + candJson("candidate:1", idx = "\"0\"") + "]"))))
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("""[{"mid":"0","idx":0,"cand":"candidate:1","extra":1}]"""))))
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("[" + candJson("candidate:1") + "]", gen = "-1"))))
        assertEquals(BAD_FIELD, rejected(decodeIce(iceJson("[" + candJson("candidate:1") + "]", gen = "${CallLimits.MAX_GEN + 1}"))))
    }

    @Test fun nestedJsonDepthIsBounded() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertEquals(BAD_JSON, rejected(decodeAsBob(rawWire(CT.rawJson("invite", "\"x\":$deep")))))
    }

    @Test fun splitIceBatchesByBytesAndCountWithFreshNonces() {
        val cands = (1..20).map { CT.cand(it, len = 250) }
        var n = 100
        val packets = CallBodyCodec.splitIce(CT.header(device = CT.A_PHONE), CT.B_PHONE, 0, cands) { CT.nonce(n++) }
        assertTrue(packets.size > 1)
        assertEquals(cands, packets.flatMap { it.candidates })
        assertEquals(packets.size, packets.map { it.header.nonce }.toSet().size)
        for (p in packets) {
            assertTrue(p.candidates.size in 1..CallLimits.MAX_CANDIDATES)
            assertTrue(CallBodyCodec.encode(p).length <= CallLimits.MAX_WIRE_CHARS)
        }
        val small = CallBodyCodec.splitIce(CT.header(), CT.B_PHONE, 0, (1..20).map { CT.cand(it) }) { CT.nonce(n++) }
        assertEquals(1, small.size)
        assertEquals(emptyList<CallPacket.Ice>(), CallBodyCodec.splitIce(CT.header(), CT.B_PHONE, 0, emptyList()) { CT.nonce(n++) })
        // 25 small candidates -> 2 packets (count cap)
        assertEquals(2, CallBodyCodec.splitIce(CT.header(), CT.B_PHONE, 0, (1..25).map { CT.cand(it) }) { CT.nonce(n++) }.size)
    }

    // ---- fuzz: never throws -------------------------------------------------------------------------------------------

    @Test fun hostileInputNeverThrows() {
        val rnd = Random(12345)
        val seeds = CT.everyOp().map { CallBodyCodec.encode(it) }
        var rejects = 0; var oks = 0
        repeat(6000) { i ->
            val seed = seeds[rnd.nextInt(seeds.size)]
            val s = when (i % 5) {
                0 -> String(CharArray(rnd.nextInt(300)) { rnd.nextInt(0x250).toChar() })
                1 -> CallLimits.PREFIX + String(CharArray(rnd.nextInt(400)) { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".random(kotlin.random.Random(rnd.nextLong())) })
                2 -> { val c = seed.toCharArray(); repeat(1 + rnd.nextInt(4)) { c[CallLimits.PREFIX.length + rnd.nextInt(c.size - CallLimits.PREFIX.length)] = (rnd.nextInt(94) + 33).toChar() }; String(c) }
                3 -> seed.substring(0, rnd.nextInt(seed.length))
                else -> { val j = CT.json(seed); val k = rnd.nextInt(j.length); CT.wireOfJson(j.substring(0, k) + (rnd.nextInt(94) + 33).toChar() + j.substring(k)) }
            }
            for (self in listOf(ALICE, BOB)) for (sender in listOf(ALICE, BOB, CT.CAROL)) {
                val r = CallBodyCodec.decode(s, CT.ctx(self, sender), NonceReplayGuard())
                if (r is CallParseResult.Ok) oks++ else rejects++
            }
        }
        assertTrue("fuzz produced rejections", rejects > 30_000)
        assertNotNull(oks)
    }
}
