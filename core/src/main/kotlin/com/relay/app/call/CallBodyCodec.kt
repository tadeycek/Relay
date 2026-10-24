package com.relay.app.call

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Bounded replay memory for packet nonces, keyed by (authenticated sender, nonce). Entries live until the
 * packet's own expiry (plus skew).
 *
 * Eviction was the first design and it was wrong: freeing the oldest UNEXPIRED entry lets an attacker push a
 * live nonce out of the cache and then replay the packet it belonged to. The guard therefore refuses new
 * nonces when it is full instead of discarding live ones - a brief denial of new call signals, never a
 * replay - and a per-sender cap stops one identity from filling the whole cache by itself.
 */
class NonceReplayGuard(val capacity: Int = 256, val perSenderCapacity: Int = 32) {
    private val seen = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0)
        require(perSenderCapacity in 1..capacity)
    }

    val size: Int @Synchronized get() = seen.size

    /**
     * True if the nonce is new (and now recorded); false if it was already seen, if this sender already holds
     * [perSenderCapacity] live nonces, or if the cache is full of unexpired entries.
     */
    @Synchronized
    fun checkAndRecord(sender: String, nonce: String, expiresMs: Long, nowMs: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value < nowMs) it.remove()
        val key = "$sender:$nonce"
        if (seen.containsKey(key)) return false
        val prefix = "$sender:"
        if (seen.keys.count { it.startsWith(prefix) } >= perSenderCapacity) return false
        if (seen.size >= capacity) return false
        seen[key] = expiresMs
        return true
    }
}

/**
 * `TYPE:CALL|J:<standard base64 of a UTF-8 JSON object>` (05 §6), strict and bounded. [decode] never throws.
 * See the KDoc of [CallLimits] for the bounds and `linux-port/evidence/WP12.md` for the field table.
 */
object CallBodyCodec {
    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val DEVICE = Regex("primary|[0-9a-f]{32}")
    private val REASON = Regex("[a-z0-9_]{1,${CallLimits.MAX_REASON_CHARS}}")
    private val MID = Regex("[A-Za-z0-9_-]{1,${CallLimits.MAX_MID_CHARS}}")
    private const val MAX_EPOCH_MS = 10_000_000_000_000L

    private val HEADER_KEYS = listOf("v", "op", "call", "ts", "expires", "caller", "callee", "device", "nonce", "media")

    private data class Shape(val required: List<String>, val optional: List<String> = emptyList())

    private val SHAPES: Map<CallOp, Shape> = mapOf(
        CallOp.INVITE to Shape(emptyList()),
        CallOp.RINGING to Shape(listOf("to")),
        CallOp.ACCEPT to Shape(listOf("to")),
        CallOp.OFFER to Shape(listOf("to", "gen", "sdp")),
        CallOp.ANSWER to Shape(listOf("to", "gen", "sdp")),
        CallOp.ICE to Shape(listOf("to", "gen", "cands")),
        CallOp.ANSWERED_ELSEWHERE to Shape(listOf("winner"), listOf("reason")),
        CallOp.DECLINE to Shape(listOf("to"), listOf("reason")),
        CallOp.BUSY to Shape(listOf("to"), listOf("reason")),
        CallOp.CANCEL to Shape(emptyList(), listOf("reason")),
        CallOp.END to Shape(listOf("to"), listOf("reason")),
        CallOp.TIMEOUT to Shape(emptyList(), listOf("reason")),
        CallOp.MUTE to Shape(listOf("to", "muted")),
    )

    fun isCall(body: String): Boolean = body.startsWith(CallLimits.PREFIX)

    // ---------------------------------------------------------------------------------------------------- encode

    /** Throws [IllegalArgumentException] if [packet] would not be accepted by a receiver (the sender never emits that). */
    fun encode(packet: CallPacket): String {
        val h = packet.header
        requireHeader(h)
        val sb = StringBuilder(512)
        sb.append("{\"v\":1,\"op\":\"").append(packet.op.wire).append("\",\"call\":\"").append(h.call)
            .append("\",\"ts\":").append(h.ts).append(",\"expires\":").append(h.expires)
            .append(",\"caller\":\"").append(h.caller).append("\",\"callee\":\"").append(h.callee)
            .append("\",\"device\":\"").append(h.device).append("\",\"nonce\":\"").append(h.nonce)
            .append("\",\"media\":\"").append(h.media).append('"')
        fun to(v: String) { require(DEVICE.matches(v)) { "bad to" }; sb.append(",\"to\":\"").append(v).append('"') }
        fun reason(v: String?) { if (v != null) { require(REASON.matches(v)) { "bad reason" }; sb.append(",\"reason\":\"").append(v).append('"') } }
        fun gen(v: Int) { require(v in 0..CallLimits.MAX_GEN) { "bad gen" }; sb.append(",\"gen\":").append(v) }
        when (packet) {
            is CallPacket.Invite -> {}
            is CallPacket.Ringing -> to(packet.to)
            is CallPacket.Accept -> to(packet.to)
            is CallPacket.Offer -> { to(packet.to); gen(packet.gen); sb.append(",\"sdp\":\"").append(encodeSdp(packet.sdp)).append('"') }
            is CallPacket.Answer -> { to(packet.to); gen(packet.gen); sb.append(",\"sdp\":\"").append(encodeSdp(packet.sdp)).append('"') }
            is CallPacket.Ice -> {
                to(packet.to); gen(packet.gen)
                require(packet.candidates.size in 1..CallLimits.MAX_CANDIDATES) { "bad candidate count" }
                sb.append(",\"cands\":[")
                packet.candidates.forEachIndexed { n, c ->
                    require(validCandidate(c)) { "bad candidate" }
                    if (n > 0) sb.append(',')
                    sb.append("{\"mid\":\"").append(c.mid).append("\",\"idx\":").append(c.index).append(",\"cand\":\"").append(escape(c.candidate)).append("\"}")
                }
                sb.append(']')
            }
            is CallPacket.AnsweredElsewhere -> {
                require(DEVICE.matches(packet.winner)) { "bad winner" }
                require(packet.reason == "answered" || packet.reason == "declined") { "bad reason" }
                sb.append(",\"winner\":\"").append(packet.winner).append("\",\"reason\":\"").append(packet.reason).append('"')
            }
            is CallPacket.Decline -> { to(packet.to); reason(packet.reason) }
            is CallPacket.Busy -> { to(packet.to); reason(packet.reason) }
            is CallPacket.Cancel -> reason(packet.reason)
            is CallPacket.End -> { to(packet.to); reason(packet.reason) }
            is CallPacket.Timeout -> reason(packet.reason)
            is CallPacket.Mute -> { to(packet.to); sb.append(",\"muted\":").append(packet.muted) }
        }
        sb.append('}')
        val json = sb.toString().toByteArray(Charsets.UTF_8)
        val wire = CallLimits.PREFIX + Base64.getEncoder().encodeToString(json)
        require(wire.length <= CallLimits.MAX_WIRE_CHARS) { "call body ${wire.length} chars exceeds ${CallLimits.MAX_WIRE_CHARS}" }
        return wire
    }

    private fun requireHeader(h: CallHeader) {
        require(HEX32.matches(h.call) && HEX32.matches(h.nonce)) { "bad call/nonce" }
        require(HEX64.matches(h.caller) && HEX64.matches(h.callee) && h.caller != h.callee) { "bad parties" }
        require(DEVICE.matches(h.device)) { "bad device" }
        require(h.media == CallLimits.MEDIA_AUDIO) { "bad media" }
        require(h.ts in 0..MAX_EPOCH_MS && h.expires in 0..MAX_EPOCH_MS && h.expires > h.ts && h.expires - h.ts <= CallLimits.MAX_LIFETIME_MS) { "bad times" }
    }

    private fun escape(s: String): String {
        val b = StringBuilder(s.length + 4)
        for (c in s) when {
            c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\")
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun validSdpText(sdp: String): Boolean {
        if (!sdp.startsWith("v=0") || sdp.isEmpty()) return false
        for (c in sdp) if (c == '\r' || c == '\n') continue else if (c < ' ' || c in '\u007f'..'\u009f') return false
        return true
    }

    private fun encodeSdp(sdp: String): String {
        val raw = sdp.toByteArray(Charsets.UTF_8)
        require(raw.size <= CallLimits.MAX_SDP_BYTES) { "sdp ${raw.size} bytes exceeds ${CallLimits.MAX_SDP_BYTES}" }
        require(validSdpText(sdp)) { "not an SDP" }
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(2048)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    internal fun validCandidate(c: IceCandidate): Boolean =
        MID.matches(c.mid) && c.index in 0..CallLimits.MAX_MLINE_INDEX && validCandidateText(c.candidate)

    private fun validCandidateText(s: String): Boolean =
        s.length <= CallLimits.MAX_CANDIDATE_CHARS && s.startsWith("candidate:") && s.all { it in ' '..'~' }

    /**
     * Splits [candidates] into [CallPacket.Ice] packets, each with at most [CallLimits.MAX_CANDIDATES] entries AND
     * an encoded size within [CallLimits.MAX_WIRE_CHARS] (WP02: split by bytes, not only by count). Every packet gets
     * a fresh nonce from [nextNonce]. Invalid candidates are dropped.
     */
    fun splitIce(header: CallHeader, to: String, gen: Int, candidates: List<IceCandidate>, nextNonce: () -> String): List<CallPacket.Ice> {
        val out = ArrayList<CallPacket.Ice>()
        var cur = ArrayList<IceCandidate>()
        fun fits(list: List<IceCandidate>): Boolean =
            runCatching { encode(CallPacket.Ice(header.copy(nonce = HEX32_SAMPLE), to, gen, list)) }.isSuccess
        fun flush() {
            if (cur.isNotEmpty()) out.add(CallPacket.Ice(header.copy(nonce = nextNonce()), to, gen, cur))
            cur = ArrayList()
        }
        for (c in candidates) {
            if (!validCandidate(c)) continue
            cur.add(c)
            if (cur.size > CallLimits.MAX_CANDIDATES || !fits(cur)) {
                cur.removeAt(cur.size - 1)
                flush()
                cur.add(c)
            }
        }
        flush()
        return out
    }

    private val HEX32_SAMPLE = "0".repeat(32)

    // ---------------------------------------------------------------------------------------------------- decode

    private class Reject(val reason: CallRejectReason) : Exception(null, null, false, false)

    private fun fail(r: CallRejectReason): Nothing = throw Reject(r)

    /** Never throws. [replay], when given, is consulted last, so rejected packets never occupy replay memory. */
    fun decode(wire: String, ctx: CallReceiveContext, replay: NonceReplayGuard? = null): CallParseResult =
        try {
            CallParseResult.Ok(decodeOrThrow(wire, ctx, replay))
        } catch (r: Reject) {
            CallParseResult.Rejected(r.reason)
        } catch (e: Exception) {
            CallParseResult.Rejected(CallRejectReason.BAD_JSON)
        }

    private fun decodeOrThrow(wire: String, ctx: CallReceiveContext, replay: NonceReplayGuard?): CallPacket {
        if (!wire.startsWith(CallLimits.PREFIX)) fail(CallRejectReason.NOT_CALL_BODY)
        if (wire.length > CallLimits.MAX_WIRE_CHARS) fail(CallRejectReason.TOO_LARGE)
        val b64 = wire.substring(CallLimits.PREFIX.length)
        if (b64.isEmpty()) fail(CallRejectReason.BAD_ENCODING)
        val bytes = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) { fail(CallRejectReason.BAD_ENCODING) }
        if (bytes.size > CallLimits.MAX_JSON_BYTES) fail(CallRejectReason.TOO_LARGE)
        val text = strictUtf8(bytes) ?: fail(CallRejectReason.BAD_JSON)
        val root = try { StrictJson.parse(text) } catch (e: StrictJson.Fail) { fail(CallRejectReason.BAD_JSON) }
        @Suppress("UNCHECKED_CAST")
        val o = (root as? Map<String, Any>) ?: fail(CallRejectReason.BAD_JSON)

        // version, then op, then the exact key set
        val v = o["v"] ?: fail(CallRejectReason.MISSING_FIELD)
        if (v !is Long) fail(CallRejectReason.BAD_FIELD)
        if (v != 1L) fail(CallRejectReason.UNKNOWN_VERSION)
        val opName = o["op"] ?: fail(CallRejectReason.MISSING_FIELD)
        if (opName !is String) fail(CallRejectReason.BAD_FIELD)
        val op = CallOp.fromWire(opName) ?: fail(CallRejectReason.UNKNOWN_OP)
        val shape = SHAPES.getValue(op)
        for (k in HEADER_KEYS + shape.required) if (!o.containsKey(k)) fail(CallRejectReason.MISSING_FIELD)
        val allowed = HEADER_KEYS.toSet() + shape.required + shape.optional
        if (o.keys.any { it !in allowed }) fail(CallRejectReason.UNKNOWN_FIELD)

        // header
        fun str(k: String, re: Regex): String { val x = o[k]; if (x !is String || !re.matches(x)) fail(CallRejectReason.BAD_FIELD); return x }
        fun time(k: String): Long { val x = o[k]; if (x !is Long || x < 0 || x > MAX_EPOCH_MS) fail(CallRejectReason.BAD_FIELD); return x }
        val call = str("call", HEX32)
        val ts = time("ts"); val expires = time("expires")
        val caller = str("caller", HEX64); val callee = str("callee", HEX64)
        val device = str("device", DEVICE); val nonce = str("nonce", HEX32)
        val media = o["media"]; if (media !is String) fail(CallRejectReason.BAD_FIELD)
        if (media != CallLimits.MEDIA_AUDIO) fail(CallRejectReason.UNSUPPORTED_MEDIA)
        val header = CallHeader(call, ts, expires, caller, callee, device, nonce, media)

        // freshness
        if (expires <= ts || expires - ts > CallLimits.MAX_LIFETIME_MS) fail(CallRejectReason.LIFETIME_TOO_LONG)
        if (ts > ctx.nowMs + ctx.skewToleranceMs) fail(CallRejectReason.FUTURE_DATED)
        if (ctx.nowMs > expires + ctx.skewToleranceMs) fail(CallRejectReason.EXPIRED)

        // binding (sender is authenticated by the transport, never read from the body)
        checkBinding(op, caller, callee, ctx)

        // op payload
        fun to(): String = str("to", DEVICE)
        fun gen(): Int { val x = o["gen"]; if (x !is Long || x < 0 || x > CallLimits.MAX_GEN) fail(CallRejectReason.BAD_FIELD); return x.toInt() }
        fun reason(): String? = if (o.containsKey("reason")) str("reason", REASON) else null
        val packet: CallPacket = when (op) {
            CallOp.INVITE -> CallPacket.Invite(header)
            CallOp.RINGING -> CallPacket.Ringing(header, to())
            CallOp.ACCEPT -> CallPacket.Accept(header, to())
            CallOp.OFFER -> CallPacket.Offer(header, to(), gen(), decodeSdp(o["sdp"]))
            CallOp.ANSWER -> CallPacket.Answer(header, to(), gen(), decodeSdp(o["sdp"]))
            CallOp.ICE -> CallPacket.Ice(header, to(), gen(), decodeCandidates(o["cands"]))
            CallOp.ANSWERED_ELSEWHERE -> {
                val r = if (o.containsKey("reason")) str("reason", REASON) else "answered"
                if (r != "answered" && r != "declined") fail(CallRejectReason.BAD_FIELD)
                CallPacket.AnsweredElsewhere(header, str("winner", DEVICE), r)
            }
            CallOp.DECLINE -> CallPacket.Decline(header, to(), reason())
            CallOp.BUSY -> CallPacket.Busy(header, to(), reason())
            CallOp.CANCEL -> CallPacket.Cancel(header, reason())
            CallOp.END -> CallPacket.End(header, to(), reason())
            CallOp.TIMEOUT -> CallPacket.Timeout(header, reason())
            CallOp.MUTE -> { val m = o["muted"]; if (m !is Boolean) fail(CallRejectReason.BAD_FIELD); CallPacket.Mute(header, to(), m) }
        }
        if (replay != null && !replay.checkAndRecord(ctx.senderPubkey, nonce, expires + ctx.skewToleranceMs, ctx.nowMs)) fail(CallRejectReason.REPLAY)
        return packet
    }

    private fun checkBinding(op: CallOp, caller: String, callee: String, ctx: CallReceiveContext) {
        val self = ctx.selfPubkey; val sender = ctx.senderPubkey
        if (caller == callee) fail(CallRejectReason.WRONG_BINDING)
        if (self != caller && self != callee) fail(CallRejectReason.WRONG_BINDING)
        if (sender != caller && sender != callee) fail(CallRejectReason.WRONG_BINDING)
        if (sender == self) {
            // The only self-addressed packet: the callee's own device telling its sibling devices the call is handled.
            if (!(op == CallOp.ANSWERED_ELSEWHERE && sender == callee)) fail(CallRejectReason.WRONG_BINDING)
            return
        }
        val ok = when (op) {
            CallOp.INVITE, CallOp.CANCEL, CallOp.TIMEOUT -> sender == caller
            CallOp.RINGING, CallOp.ACCEPT, CallOp.DECLINE, CallOp.BUSY -> sender == callee
            CallOp.OFFER, CallOp.ANSWER, CallOp.ICE, CallOp.END, CallOp.MUTE, CallOp.ANSWERED_ELSEWHERE -> true
        }
        // answered_elsewhere between the two parties is only valid from the caller (the callee's own copy is a self signal).
        if (!ok || (op == CallOp.ANSWERED_ELSEWHERE && sender != caller)) fail(CallRejectReason.WRONG_BINDING)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) { null }

    private fun decodeSdp(x: Any?): String {
        if (x !is String) fail(CallRejectReason.BAD_FIELD)
        val compressed = try { Base64.getDecoder().decode(x) } catch (e: IllegalArgumentException) { fail(CallRejectReason.BAD_SDP) }
        if (compressed.isEmpty()) fail(CallRejectReason.BAD_SDP)
        val inf = Inflater()
        try {
            inf.setInput(compressed)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(2048)
            while (!inf.finished()) {
                val n = try { inf.inflate(buf) } catch (e: DataFormatException) { fail(CallRejectReason.BAD_SDP) }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) fail(CallRejectReason.BAD_SDP)
                if (out.size() + n > CallLimits.MAX_SDP_BYTES) fail(CallRejectReason.SDP_TOO_LARGE)
                out.write(buf, 0, n)
            }
            if (inf.remaining > 0) fail(CallRejectReason.BAD_SDP)
            val sdp = strictUtf8(out.toByteArray()) ?: fail(CallRejectReason.BAD_SDP)
            if (!validSdpText(sdp)) fail(CallRejectReason.BAD_SDP)
            return sdp
        } finally { inf.end() }
    }

    private fun decodeCandidates(x: Any?): List<IceCandidate> {
        if (x !is List<*>) fail(CallRejectReason.BAD_FIELD)
        if (x.isEmpty()) fail(CallRejectReason.BAD_FIELD)
        if (x.size > CallLimits.MAX_CANDIDATES) fail(CallRejectReason.TOO_MANY_CANDIDATES)
        return x.map { e ->
            if (e !is Map<*, *> || e.keys != setOf("mid", "idx", "cand")) fail(CallRejectReason.BAD_FIELD)
            val mid = e["mid"]; val idx = e["idx"]; val cand = e["cand"]
            if (mid !is String || cand !is String || idx !is Long) fail(CallRejectReason.BAD_FIELD)
            val c = IceCandidate(mid, if (idx in Int.MIN_VALUE..Int.MAX_VALUE) idx.toInt() else -1, cand)
            if (!validCandidate(c)) fail(CallRejectReason.BAD_CANDIDATE)
            c
        }
    }
}
