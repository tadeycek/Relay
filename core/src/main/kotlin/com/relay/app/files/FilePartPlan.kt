package com.relay.app.files


/** Stable file planning limits. Raw framing is smaller than the historical JPEG calculation, but full
 * plaintext part length is intentionally frozen for deterministic cross-version retries and resume. */
object FilePartPlan {
    /** Stable plaintext part length (not the maximum raw framing could fit). */
    const val PART_PLAIN_BYTES = 26_212_814
    const val MAX_PARTS = 5
    const val MAX_SIZE = 100L * 1024 * 1024
    /** Mirrors `maxMediaBytes` in server/cloudflare/src/protocol.ts (25 MiB + 64). */
    const val SERVER_BLOB_CAP = 25L * 1024 * 1024 + 64
    /** AES-GCM payload framing (raw magic/version adds another 5 bytes): 12-byte IV in front, 16-byte tag behind. */
    const val CRYPTO_OVERHEAD = 12 + 16

    data class Part(val index: Int, val offset: Long, val length: Int, val storedBytes: Int)

    /** Bytes of the stored blob (raw header included) for a part of [plain] plaintext bytes. */
    fun storedSize(plain: Int): Int = plain + CRYPTO_OVERHEAD + 5

    /** Inverse of [storedSize]; null when no plaintext length in 0..[PART_PLAIN_BYTES] produces [stored]. */
    fun plainSize(stored: Int): Int? {
        if (stored < storedSize(0) || stored > storedSize(PART_PLAIN_BYTES)) return null
        var lo = 0; var hi = PART_PLAIN_BYTES
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (storedSize(mid) < stored) lo = mid + 1 else hi = mid
        }
        return if (storedSize(lo) == stored) lo else null
    }

    /** 0 when [size] is outside 0..[MAX_SIZE]. */
    fun partCount(size: Long): Int =
        if (size < 0 || size > MAX_SIZE) 0 else if (size == 0L) 1 else ((size + PART_PLAIN_BYTES - 1) / PART_PLAIN_BYTES).toInt()

    /** Every part but the last is exactly [PART_PLAIN_BYTES]; null when [size] is outside 0..[MAX_SIZE]. */
    fun plan(size: Long): List<Part>? {
        val n = partCount(size)
        if (n == 0) return null
        return (0 until n).map { i ->
            val offset = i.toLong() * PART_PLAIN_BYTES
            val len = minOf(PART_PLAIN_BYTES.toLong(), size - offset).toInt()
            Part(i, offset, len, storedSize(len))
        }
    }
}
