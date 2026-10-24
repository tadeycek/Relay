package com.relay.app.call

import com.relay.app.call.CT.ALICE
import com.relay.app.call.CT.BOB
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

/**
 * Pins the `TYPE:CALL` wire format in `src/test/resources/golden/call-codecs.golden` (a separate file from the other
 * codecs' goldens). Record with `RELAY_GOLDEN_RECORD=1`; otherwise only compared. Every inputs is synthetic. The SDP
 * vector uses a STORED (level 0) deflate stream, whose bytes are identical across zlib versions, so the vector is a
 * stable interop target for the Android and desktop hosts.
 */
class CallCodecsGoldenTest {
    private val lines = ArrayList<String>()
    private fun esc(s: String) = buildString { for (c in s) if (c.code in 0x20..0x7e && c != '\\') append(c) else append("\\u%04x".format(c.code)) }
    private fun rec(name: String, v: Any?) { lines += "$name\t${esc(v.toString())}" }

    private fun storedDeflateB64(raw: ByteArray): String {
        val d = Deflater(Deflater.NO_COMPRESSION); d.setInput(raw); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun parse(wire: String, self: String = BOB, sender: String = ALICE, now: Long = CT.NOW + 1000, g: NonceReplayGuard? = null): String =
        when (val r = CallBodyCodec.decode(wire, CT.ctx(self, sender, now), g)) {
            is CallParseResult.Ok -> "Ok(${r.packet.op.wire})"
            is CallParseResult.Rejected -> "Rejected(${r.reason})"
        }

    @Test fun golden() {
        rec("const.PREFIX", CallLimits.PREFIX); rec("const.MAX_WIRE_CHARS", CallLimits.MAX_WIRE_CHARS); rec("const.MAX_JSON_BYTES", CallLimits.MAX_JSON_BYTES)
        rec("const.MAX_SDP_BYTES", CallLimits.MAX_SDP_BYTES); rec("const.MAX_CANDIDATES", CallLimits.MAX_CANDIDATES)
        rec("const.MAX_CANDIDATE_CHARS", CallLimits.MAX_CANDIDATE_CHARS); rec("const.INVITE_TTL_MS", CallLimits.INVITE_TTL_MS)
        rec("const.SIGNAL_TTL_MS", CallLimits.SIGNAL_TTL_MS); rec("const.MAX_LIFETIME_MS", CallLimits.MAX_LIFETIME_MS)
        rec("const.DEFAULT_SKEW_MS", CallLimits.DEFAULT_SKEW_MS)

        for (p in CT.everyOp()) {
            if (p is CallPacket.Offer || p is CallPacket.Answer) continue
            val wire = CallBodyCodec.encode(p)
            rec("encode.${p.op.wire}.json", CT.json(wire)); rec("encode.${p.op.wire}.wire", wire)
            assertEquals(p, (CallBodyCodec.decode(wire, CT.ctx(CT.receiverOf(p), CT.senderOf(p))) as CallParseResult.Ok).packet)
        }

        // SDP interop vector: stored deflate of the synthetic SDP, wrapped exactly as a sender must.
        val sdpB64 = storedDeflateB64(CT.SDP.toByteArray())
        val offerJson = """{"v":1,"op":"offer","call":"${CT.callId(1)}","ts":${CT.NOW},"expires":${CT.NOW + 45_000},"caller":"$ALICE","callee":"$BOB","device":"${CT.A_PHONE}","nonce":"${CT.nonce(4)}","media":"audio","to":"${CT.B_PHONE}","gen":0,"sdp":"$sdpB64"}"""
        val offerWire = CT.wireOfJson(offerJson)
        rec("vector.sdp.raw.sha256", java.security.MessageDigest.getInstance("SHA-256").digest(CT.SDP.toByteArray()).joinToString("") { "%02x".format(it) })
        rec("vector.sdp.raw.bytes", CT.SDP.toByteArray().size); rec("vector.offer.storedDeflate.wireChars", offerWire.length)
        rec("vector.offer.storedDeflate.wire", offerWire)
        val decoded = (CallBodyCodec.decode(offerWire, CT.ctx(BOB, ALICE)) as CallParseResult.Ok).packet as CallPacket.Offer
        assertEquals(CT.SDP, decoded.sdp)
        rec("vector.offer.decoded", "gen=${decoded.gen} to=${decoded.to} device=${decoded.header.device} sdpEqual=${decoded.sdp == CT.SDP}")
        // The encoder (default deflate level) must stay inside the bound for the same SDP; exact bytes depend on the zlib build.
        rec("encode.offer.withinBound", CallBodyCodec.encode(CallPacket.Offer(CT.header(), CT.B_PHONE, 0, CT.SDP)).length <= CallLimits.MAX_WIRE_CHARS)

        // Candidate batching: 20 candidates of 250 chars split by bytes.
        var n = 100
        val split = CallBodyCodec.splitIce(CT.header(), CT.B_PHONE, 0, (1..20).map { CT.cand(it, 250) }) { CT.nonce(n++) }
        rec("split.ice.250x20", split.joinToString(";") { it.candidates.size.toString() })
        rec("split.ice.250x20.maxWire", split.maxOf { CallBodyCodec.encode(it).length })
        rec("split.ice.25small", CallBodyCodec.splitIce(CT.header(), CT.B_PHONE, 0, (1..25).map { CT.cand(it) }) { CT.nonce(n++) }.joinToString(";") { it.candidates.size.toString() })

        // Hostile / boundary parse table (receiver BOB, sender ALICE, now = ts + 1 s, skew 10 s).
        val good = CallBodyCodec.encode(CallPacket.Invite(CT.header()))
        fun h(name: String, wire: String, self: String = BOB, sender: String = ALICE, now: Long = CT.NOW + 1000, g: NonceReplayGuard? = null) = rec("parse.$name", parse(wire, self, sender, now, g))
        h("good", good); h("empty", ""); h("notCall", "TYPE:FILE|x"); h("badBase64", "TYPE:CALL|J:@@@")
        h("oversize", CallLimits.PREFIX + "A".repeat(4100))
        h("v2", CT.wireOfJson(CT.rawJson("invite", v = "2"))); h("v0", CT.wireOfJson(CT.rawJson("invite", v = "0")))
        h("unknownOp", CT.wireOfJson(CT.rawJson("group_start"))); h("video", CT.wireOfJson(CT.rawJson("invite", override = mapOf("media" to "\"video\""))))
        h("unknownField", CT.wireOfJson(CT.rawJson("invite", "\"x\":1")))
        h("expired", good, now = CT.NOW + 45_000 + 10_001); h("expiredAtSkewEdge", good, now = CT.NOW + 45_000 + 10_000)
        h("future", good, now = CT.NOW - 10_001); h("futureAtSkewEdge", good, now = CT.NOW - 10_000)
        h("lifetime61s", CT.wireOfJson(CT.rawJson("invite", override = mapOf("expires" to "${CT.NOW + 60_001}"))))
        h("wrongSender", good, sender = CT.CAROL); h("wrongReceiver", good, self = CT.CAROL); h("calleeSendsInvite", good, self = ALICE, sender = BOB)
        val g = NonceReplayGuard(); h("replay.first", good, g = g); h("replay.second", good, g = g)
        h("sdpBomb", CT.wireOfJson(CT.rawJson("offer", "\"to\":\"${CT.B_PHONE}\",\"gen\":0,\"sdp\":\"${CT.deflateB64(ByteArray(CallLimits.MAX_SDP_BYTES + 1) { 'a'.code.toByte() })}\"")))
        h("sdpNotSdp", CT.wireOfJson(CT.rawJson("offer", "\"to\":\"${CT.B_PHONE}\",\"gen\":0,\"sdp\":\"${CT.deflateB64("hello".toByteArray())}\"")))
        val cands21 = (1..21).joinToString(",", "[", "]") { """{"mid":"0","idx":0,"cand":"candidate:$it"}""" }
        h("candidates21", CT.wireOfJson(CT.rawJson("ice", "\"to\":\"${CT.B_PHONE}\",\"gen\":0,\"cands\":$cands21")))

        val text = lines.joinToString("\n") + "\n"
        val golden = File("src/test/resources/golden/call-codecs.golden")
        if (System.getenv("RELAY_GOLDEN_RECORD") == "1") { golden.writeText(text); println("GOLDEN RECORDED: ${lines.size} entries -> ${golden.absolutePath}") }
        assertEquals(golden.readText(), text)
    }
}
