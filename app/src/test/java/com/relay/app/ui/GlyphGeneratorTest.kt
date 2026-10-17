package com.relay.app.ui

import com.relay.app.ui.glyph.Corner
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.glyph.GlyphSpec
import com.relay.app.ui.glyph.GlyphState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GlyphGeneratorTest {

    private val keyA = "9a66b364".repeat(8)
    private val keyB = "0123abcd".repeat(8)

    @Test
    fun sameKeyAlwaysGivesTheSameGlyph() {
        assertEquals(GlyphGenerator.fromKey(keyA), GlyphGenerator.fromKey(keyA))
    }

    @Test
    fun caseAndSurroundingWhitespaceDoNotChangeTheGlyph() {
        assertEquals(GlyphGenerator.fromKey(keyA), GlyphGenerator.fromKey("  " + keyA.uppercase() + "\n"))
    }

    @Test
    fun differentKeysGiveDifferentGlyphs() {
        assertNotEquals(GlyphGenerator.fromKey(keyA), GlyphGenerator.fromKey(keyB))
    }

    @Test
    fun aChangedKeyChangesTheGlyph() {
        // The glyph doubles as a visible "key changed" signal: flipping one character must show.
        val changed = keyA.replaceFirst('9', '8')
        assertNotEquals(GlyphGenerator.fromKey(keyA), GlyphGenerator.fromKey(changed))
    }

    @Test
    fun aGlyphHasSixteenCellsAllWithinTheFourVariants() {
        val spec = GlyphGenerator.fromKey(keyA)
        assertEquals(16, spec.cells.size)
        assertTrue(spec.cells.all { it in 0..3 })
        assertEquals(spec.cells[5], spec.variantAt(1, 1))
    }

    @Test
    fun anyInputYieldsAValidGlyphIncludingEmptyAndUnicode() {
        for (s in listOf("", " ", "Ana", "Žiga Škofič 🙂", "x".repeat(10_000))) {
            assertEquals(16, GlyphGenerator.fromKey(s).cells.size)
        }
    }

    @Test
    fun manyRandomKeysRarelyCollideAndUseAllVariantsRoughlyEvenly() {
        val rnd = Random(42)
        val seen = HashSet<GlyphSpec>()
        val variantCounts = IntArray(4)
        val n = 2000
        repeat(n) {
            val key = (1..64).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
            val spec = GlyphGenerator.fromKey(key)
            seen.add(spec)
            spec.cells.forEach { variantCounts[it]++ }
        }
        // 2000 keys over about 4.3 billion patterns: a collision is astronomically unlikely.
        assertEquals(n, seen.size)
        val total = n * 16
        for (c in variantCounts) {
            val share = c.toDouble() / total
            assertTrue("variant share $share should be near 0.25", share in 0.22..0.28)
        }
    }

    @Test
    fun everyVariantDrawsExactlyTwoCornersAndTheFourVariantsAreDistinct() {
        val sets = (0..3).map { GlyphGenerator.cornersFor(it) }
        assertTrue(sets.all { it.size == 2 })
        assertEquals(4, sets.toSet().size)
        assertTrue(Corner.TOP_LEFT in sets[0] && Corner.BOTTOM_RIGHT in sets[0])
    }

    @Test
    fun invalidVariantsAreRejected() {
        try {
            GlyphGenerator.cornersFor(4)
            assertFalse("expected an exception", true)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun specRejectsWrongSizeOrOutOfRangeCells() {
        for (bad in listOf(List(15) { 0 }, List(16) { 4 }, List(16) { -1 })) {
            try {
                GlyphSpec(bad)
                assertFalse("expected an exception for $bad", true)
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun trustStateFollowsKeyAndVerification() {
        assertEquals(GlyphState.VERIFIED, GlyphState.forContact(hasNostrKey = true, verifiedInPerson = true))
        assertEquals(GlyphState.UNVERIFIED, GlyphState.forContact(hasNostrKey = true, verifiedInPerson = false))
        assertEquals(GlyphState.NEUTRAL, GlyphState.forContact(hasNostrKey = false, verifiedInPerson = true))
        assertEquals(GlyphState.NEUTRAL, GlyphState.forContact(hasNostrKey = false, verifiedInPerson = false))
    }

    @Test
    fun seedPrefersKeyThenPhoneThenName() {
        assertEquals("key", GlyphGenerator.seedFor("key", "+386", "Ana"))
        assertEquals("+386", GlyphGenerator.seedFor(null, "+386", "Ana"))
        assertEquals("+386", GlyphGenerator.seedFor("  ", "+386", "Ana"))
        assertEquals("Ana", GlyphGenerator.seedFor(null, null, "Ana"))
        assertEquals("Ana", GlyphGenerator.seedFor(null, "", "Ana"))
    }
}
