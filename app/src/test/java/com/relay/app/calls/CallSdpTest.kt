package com.relay.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media settings for a voice call: Opus mono, DTX and FEC, bounded bitrate, audio m-line only. */
class CallSdpTest {

    private val offer = listOf(
        "v=0",
        "o=- 1 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2",
        "",
    ).joinToString("\r\n")

    @Test fun opusIsAskedForMonoDtxFecAndA32KBitCeiling() {
        val tuned = CallSdp.audioTuning(offer)
        assertTrue(tuned.contains("stereo=0"))
        assertTrue(tuned.contains("usedtx=1"))
        assertTrue(tuned.contains("useinbandfec=1"))
        assertTrue(tuned.contains("maxaveragebitrate=32000"))
        // Existing parameters are kept, not replaced.
        assertTrue(tuned.contains("minptime=10"))
        assertEquals(tuned, CallSdp.audioTuning(tuned))
    }

    @Test fun anAnswerWithoutAnFmtpLineGetsOne() {
        val bare = offer.lines().filterNot { it.startsWith("a=fmtp:") }.joinToString("\r\n")
        val tuned = CallSdp.audioTuning(bare)
        assertTrue(tuned.contains("a=fmtp:111 "))
        assertTrue(tuned.contains("usedtx=1"))
        assertEquals(tuned, CallSdp.audioTuning(tuned))
    }

    @Test fun aSessionWithoutOpusIsLeftAlone() {
        val other = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 0\r\na=rtpmap:0 PCMU/8000\r\n"
        assertEquals(other, CallSdp.audioTuning(other))
    }

    @Test fun onlyAudioMayBeNegotiated() {
        assertTrue(CallSdp.isAudioOnly(offer))
        assertFalse(CallSdp.isAudioOnly(offer + "\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
    }
}
