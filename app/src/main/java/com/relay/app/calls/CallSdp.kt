package com.relay.app.calls

/**
 * SDP tuning for a Relay call: Opus **mono**, DTX on and FEC on at a 24–32 kbit/s budget (06 §1).
 * libwebrtc's defaults are stereo-capable Opus without DTX, so the offer/answer is rewritten before it
 * is sent. Pure string work, so it is unit-tested rather than trusted.
 */
object CallSdp {

    /** Opus `maxaveragebitrate` is in bits per second: 32 kbit/s for a voice call. */
    const val MAX_AVERAGE_BITRATE = 32_000
    private val OPUS_RTPMAP = Regex("^a=rtpmap:(\\d+) opus/48000/2$", RegexOption.IGNORE_CASE)

    /** Adds the missing Opus parameters. Applying it twice returns the same SDP. */
    fun audioTuning(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val pt = lines.firstNotNullOfOrNull { OPUS_RTPMAP.find(it.trim())?.groupValues?.get(1) } ?: return sdp
        val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
        val wanted = linkedMapOf(
            "stereo" to "0",
            "sprop-stereo" to "0",
            "usedtx" to "1",
            "useinbandfec" to "1",
            "maxaveragebitrate" to MAX_AVERAGE_BITRATE.toString(),
        )
        if (fmtpIndex < 0) {
            lines.add(Math.max(0, lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") } + 1),
                "a=fmtp:$pt " + wanted.entries.joinToString(";") { "${it.key}=${it.value}" })
            return lines.joinToString("\r\n")
        }
        val existing = lines[fmtpIndex].removePrefix("a=fmtp:$pt ").split(";").filter { it.isNotBlank() }.toMutableList()
        val present = existing.mapNotNull { it.substringBefore("=").takeIf(String::isNotEmpty) }.toSet()
        for ((key, value) in wanted) {
            if (key !in present) existing += "$key=$value"
        }
        lines[fmtpIndex] = "a=fmtp:$pt " + existing.joinToString(";")
        return lines.joinToString("\r\n")
    }

    /** False for a session that negotiates anything besides audio (a video m-line would open a camera path). */
    fun isAudioOnly(sdp: String): Boolean =
        sdp.split("\r\n").none { it.startsWith("m=") && !it.startsWith("m=audio ") }
}
