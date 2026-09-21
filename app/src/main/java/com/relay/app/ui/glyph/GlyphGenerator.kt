package com.relay.app.ui.glyph

import java.security.MessageDigest

/** Which corners of a tile carry a quarter-circle arc. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** How much to trust the person a glyph stands for; decides how it is drawn. */
enum class GlyphState {
    /** Met in person (their QR code was scanned). */
    VERIFIED,

    /** Knows our key but was never scanned. */
    UNVERIFIED,

    /** You. */
    SELF,

    /** No trust information (legacy contacts without a key). */
    NEUTRAL;

    companion object {
        fun forContact(hasNostrKey: Boolean, verifiedInPerson: Boolean): GlyphState = when {
            !hasNostrKey -> NEUTRAL
            verifiedInPerson -> VERIFIED
            else -> UNVERIFIED
        }
    }
}

/**
 * The pattern behind a contact's avatar: a [GRID] by [GRID] grid of tiles, each drawn from one of four
 * pairs of quarter-circle arcs, so neighbouring tiles join into flowing curves. It is derived from the
 * contact's key, so the same person looks identical on every phone and a changed key visibly changes it.
 *
 * It is a recognition aid, not proof of identity: with 4^16 (about 4.3 billion) patterns a friend group
 * will not collide by accident, but the Relay ID fingerprint is what actually verifies someone.
 */
data class GlyphSpec(val cells: List<Int>) {
    init {
        require(cells.size == GRID * GRID) { "a glyph has ${GRID * GRID} cells" }
        require(cells.all { it in 0 until VARIANTS }) { "cell variants are 0 until $VARIANTS" }
    }

    fun variantAt(row: Int, col: Int): Int = cells[row * GRID + col]

    companion object {
        const val GRID = 4
        const val VARIANTS = 4
    }
}

object GlyphGenerator {

    /** Deterministic: SHA-256 of the key, two bits per cell. Any string (even empty) yields a valid glyph. */
    fun fromKey(key: String): GlyphSpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.trim().lowercase().toByteArray(Charsets.UTF_8))
        val cells = List(GlyphSpec.GRID * GlyphSpec.GRID) { i ->
            val byte = digest[i / 4].toInt() and 0xFF
            (byte shr ((i % 4) * 2)) and 0b11
        }
        return GlyphSpec(cells)
    }

    /** The two corners drawn for a tile variant. */
    fun cornersFor(variant: Int): Set<Corner> = when (variant) {
        0 -> setOf(Corner.TOP_LEFT, Corner.BOTTOM_RIGHT)
        1 -> setOf(Corner.TOP_RIGHT, Corner.BOTTOM_LEFT)
        2 -> setOf(Corner.TOP_LEFT, Corner.TOP_RIGHT)
        3 -> setOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)
        else -> throw IllegalArgumentException("variant $variant")
    }

    /** What a contact's glyph is seeded with: their Nostr key, else their number, else their name. */
    fun seedFor(nostrPubkey: String?, phone: String?, name: String): String =
        nostrPubkey?.takeIf { it.isNotBlank() } ?: phone?.takeIf { it.isNotBlank() } ?: name
}
