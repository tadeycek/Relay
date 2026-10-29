package com.relay.app.call.group.e2ee

import java.math.BigInteger

enum class ReplayDecision { ACCEPTED, DUPLICATE, TOO_OLD, INVALID, STREAM_LIMIT }

/**
 * Bounded replay window for one authenticated frame stream.
 *
 * Bit 0 of the mask is [highest]; bits 1..size-1 are the preceding counters. A counter below
 * `highest - size + 1` is TOO_OLD, a counter already present is DUPLICATE, a jump larger than the window
 * resets it (the intermediate counters can no longer be distinguished and are treated as old).
 */
class FrameReplayWindow(val size: Int = DEFAULT_SIZE) {
    init {
        require(size in 1..4096) { "replay window size out of range" }
    }

    private var highest: Long = -1
    private var mask: BigInteger = BigInteger.ZERO
    private val limit: BigInteger = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE)

    val highestSeen: Long get() = highest

    fun admit(counter: Long): ReplayDecision {
        if (counter < 0) return ReplayDecision.INVALID
        if (highest < 0) {
            highest = counter
            mask = BigInteger.ONE
            return ReplayDecision.ACCEPTED
        }
        if (counter > highest) {
            val delta = counter - highest
            mask = if (delta >= size) BigInteger.ONE
            else mask.shiftLeft(delta.toInt()).or(BigInteger.ONE).and(limit)
            highest = counter
            return ReplayDecision.ACCEPTED
        }
        val distance = highest - counter
        if (distance >= size) return ReplayDecision.TOO_OLD
        val bit = distance.toInt()
        if (mask.testBit(bit)) return ReplayDecision.DUPLICATE
        mask = mask.setBit(bit).and(limit)
        return ReplayDecision.ACCEPTED
    }

    fun reset() {
        highest = -1
        mask = BigInteger.ZERO
    }

    companion object {
        const val DEFAULT_SIZE = 256
    }
}

/**
 * Extends 16-bit RTP sequence numbers to a monotonic counter per SSRC (RFC 3550-style unwrapping):
 * a drop of more than 32768 is a wrap (+65536), a rise of more than 32767 is a late pre-wrap packet.
 */
class RtpSequenceExtender {
    private var highestExtended: Long = -1
    private var lastRaw: Int = -1

    fun extend(rawSequenceNumber: Int): Long {
        require(rawSequenceNumber in 0..0xFFFF) { "RTP sequence number must be 16-bit" }
        if (lastRaw < 0) {
            lastRaw = rawSequenceNumber
            highestExtended = rawSequenceNumber.toLong()
            return highestExtended
        }
        var delta = rawSequenceNumber - lastRaw
        if (delta < -32768) delta += 65536
        else if (delta > 32767) delta -= 65536
        val extended = highestExtended + delta
        if (extended > highestExtended) {
            highestExtended = extended
            lastRaw = rawSequenceNumber
        }
        return extended
    }

    fun reset() {
        highestExtended = -1
        lastRaw = -1
    }
}

/**
 * Replay guard across call participants. **Call [admit] only after [GroupFrameCrypto.open] returned
 * `Opened`.** Checking before authentication would let a forged high-counter frame advance the window
 * (a trivial denial of service against the real stream).
 *
 * The encoded frame trailer has no counter, so the counter comes from the host:
 * - desktop: `RTCEncodedAudioFrame.getSsrc()` and `getSequenceNumber()` passed straight in; the 16-bit
 *   sequence number is extended per SSRC inside this class.
 * - Android: `FrameCryptor` exposes no encoded-frame transform or per-frame sequence number, so the Android
 *   host cannot call this guard per frame today. That is a documented gap; see linux-port/evidence/WP17.md.
 *
 * Windows are keyed by sender device + epoch + SSRC, so a new epoch starts clean and two devices of one
 * member never share a window. When [maxStreams] is reached a new stream is rejected (STREAM_LIMIT) instead
 * of evicting an active window; call [removeEpochsBelow] on rotation and [clear] on call end.
 */
class GroupCallReplayGuard(
    private val windowSize: Int = FrameReplayWindow.DEFAULT_SIZE,
    private val maxStreams: Int = 128,
) {
    private data class StreamKey(val sender: ParticipantDevice, val epoch: Int, val ssrc: Long)

    private inner class Stream {
        val extender = RtpSequenceExtender()
        val window = FrameReplayWindow(windowSize)
    }

    private val streams = LinkedHashMap<StreamKey, Stream>()

    init {
        require(maxStreams in 1..4096) { "maxStreams out of range" }
    }

    @Synchronized
    fun admit(sender: ParticipantDevice, epoch: Int, ssrc: Long, rtpSequenceNumber: Int): ReplayDecision {
        if (ssrc !in 0..0xFFFF_FFFFL || epoch !in 0..GroupCallKeySchedule.MAX_EPOCH) {
            return ReplayDecision.INVALID
        }
        val key = StreamKey(sender, epoch, ssrc)
        val stream = streams[key] ?: run {
            if (streams.size >= maxStreams) return ReplayDecision.STREAM_LIMIT
            Stream().also { streams[key] = it }
        }
        return stream.window.admit(stream.extender.extend(rtpSequenceNumber))
    }

    @Synchronized
    fun removeEpochsBelow(epoch: Int) {
        streams.keys.removeIf { it.epoch < epoch }
    }

    @Synchronized
    fun clear() {
        streams.clear()
    }

    @Synchronized
    fun streamCount(): Int = streams.size
}
