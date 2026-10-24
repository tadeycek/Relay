package com.relay.app.call

import java.util.Base64

/** Shared fixtures. All keys, ids and nonces are synthetic. */
object CT {
    val ALICE = "a1".repeat(32)
    val BOB = "b2".repeat(32)
    val CAROL = "c3".repeat(32)
    val A_PHONE = "11".repeat(16)
    val B_PHONE = "22".repeat(16)
    val B_DESK = "33".repeat(16)
    val B_LAP = "44".repeat(16)
    const val NOW = 1_800_000_000_000L

    fun callId(n: Int) = "%032x".format(n)
    fun nonce(n: Int) = "%032x".format(0x7000_0000L + n)

    fun header(
        call: String = callId(1), ts: Long = NOW, expires: Long = NOW + CallLimits.INVITE_TTL_MS,
        caller: String = ALICE, callee: String = BOB, device: String = A_PHONE, nonce: String = nonce(1),
        media: String = "audio",
    ) = CallHeader(call, ts, expires, caller, callee, device, nonce, media)

    val SDP: String = buildString {
        append("v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=extmap-allow-mixed\r\n")
        append("a=msid-semantic: WMS\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
        append("a=ice-ufrag:abcd\r\na=ice-pwd:0123456789abcdef012345\r\na=ice-options:trickle\r\n")
        append("a=fingerprint:sha-256 " + (0 until 32).joinToString(":") { "%02X".format(it * 7 + 3) } + "\r\n")
        append("a=setup:actpass\r\na=mid:0\r\na=sendrecv\r\na=rtcp-mux\r\n")
        append("a=rtpmap:111 opus/48000/2\r\na=rtcp-fb:111 transport-cc\r\na=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;stereo=0;maxaveragebitrate=32000\r\n")
        append("a=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\na=rtpmap:9 G722/8000\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n")
        append("a=rtpmap:13 CN/8000\r\na=rtpmap:110 telephone-event/48000\r\na=rtpmap:126 telephone-event/8000\r\n")
        append("a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\na=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time\r\n")
        append("a=ssrc:1234567890 cname:synthetic\r\n")
    }

    fun cand(i: Int, len: Int = 0): IceCandidate {
        val base = "candidate:$i 1 udp 2122260223 192.0.2.${i % 250} ${50000 + i} typ host generation 0"
        return IceCandidate("0", 0, if (len > base.length) base + " ufrag " + "x".repeat(len - base.length - 7) else base)
    }

    /** One packet per op, all valid for caller ALICE -> callee BOB with BOB's phone as the answering device. */
    fun everyOp(): List<CallPacket> {
        val toA = A_PHONE; val toB = B_PHONE
        fun h(n: Int, from: String) = header(nonce = nonce(n), device = from)
        return listOf(
            CallPacket.Invite(h(1, A_PHONE)),
            CallPacket.Ringing(h(2, B_PHONE), toA),
            CallPacket.Accept(h(3, B_PHONE), toA),
            CallPacket.Offer(h(4, A_PHONE), toB, 0, SDP),
            CallPacket.Answer(h(5, B_PHONE), toA, 0, SDP),
            CallPacket.Ice(h(6, A_PHONE), toB, 0, listOf(cand(1), cand(2))),
            CallPacket.AnsweredElsewhere(h(7, A_PHONE), B_PHONE),
            CallPacket.Decline(h(8, B_PHONE), toA, "declined"),
            CallPacket.Busy(h(9, B_PHONE), toA, "busy"),
            CallPacket.Cancel(h(10, A_PHONE), "hangup"),
            CallPacket.End(h(11, A_PHONE), toB, "hangup"),
            CallPacket.Timeout(h(12, A_PHONE), "no_answer"),
            CallPacket.Mute(h(13, B_PHONE), toA, true),
        )
    }

    /** Which identity authenticates as the sender of [p] in [everyOp]. */
    fun senderOf(p: CallPacket): String = if (p.header.device == A_PHONE) ALICE else BOB

    fun receiverOf(p: CallPacket): String = if (senderOf(p) == ALICE) BOB else ALICE

    fun ctx(self: String, sender: String, now: Long = NOW + 1000, skew: Long = CallLimits.DEFAULT_SKEW_MS) =
        CallReceiveContext(self, sender, now, skew)

    fun json(wire: String): String = String(Base64.getDecoder().decode(wire.removePrefix(CallLimits.PREFIX)), Charsets.UTF_8)

    fun wireOfJson(json: String): String = CallLimits.PREFIX + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    fun deflateB64(raw: ByteArray): String {
        val d = java.util.zip.Deflater(9); d.setInput(raw); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** A hand-built JSON for hostile tests: header fields + [extra] (already JSON text, no leading comma). */
    fun rawJson(op: String, extra: String = "", v: String = "1", override: Map<String, String> = emptyMap(), drop: Set<String> = emptySet()): String {
        val fields = linkedMapOf(
            "v" to v, "op" to "\"$op\"", "call" to "\"${callId(1)}\"", "ts" to "$NOW", "expires" to "${NOW + 45_000}",
            "caller" to "\"$ALICE\"", "callee" to "\"$BOB\"", "device" to "\"$A_PHONE\"", "nonce" to "\"${nonce(1)}\"", "media" to "\"audio\"",
        )
        override.forEach { (k, x) -> fields[k] = x }
        drop.forEach { fields.remove(it) }
        val body = fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return "{" + body + (if (extra.isEmpty()) "" else ",$extra") + "}"
    }
}
