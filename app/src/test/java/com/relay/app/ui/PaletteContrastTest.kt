package com.relay.app.ui

import com.relay.app.ui.theme.ContrastMath
import com.relay.app.ui.theme.PaletteSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {

    private val themes = mapOf("dark" to PaletteSpec.dark, "light" to PaletteSpec.light)

    @Test
    fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, ContrastMath.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, ContrastMath.ratio(0xFF123456, 0xFF123456), 0.0001)
        // Symmetric: order does not matter.
        assertEquals(ContrastMath.ratio(0xFF336699, 0xFFEEEEEE), ContrastMath.ratio(0xFFEEEEEE, 0xFF336699), 1e-9)
    }

    @Test
    fun bodyTextMeetsWcagAaOnEverySurfaceInBothThemes() {
        for ((name, p) in themes) {
            for (surface in listOf(p.background, p.surface1, p.surface2, p.surface3)) {
                for ((role, fg) in listOf("primary" to p.textPrimary, "secondary" to p.textSecondary)) {
                    val r = ContrastMath.ratio(fg, surface)
                    assertTrue("$name $role text on ${surface.toString(16)} is only %.2f".format(r), r >= 4.5)
                }
            }
        }
    }

    @Test
    fun meaningfulColoursAreReadableAsTextOnTheBackgroundAndListSurface() {
        for ((name, p) in themes) {
            for (surface in listOf(p.background, p.surface1)) {
                assertTrue("$name verified", ContrastMath.ratio(p.verified, surface) >= 4.5)
                assertTrue("$name danger", ContrastMath.ratio(p.danger, surface) >= 4.5)
            }
        }
    }

    @Test
    fun accentFillsHaveReadableContentOnTopOfThem() {
        for ((name, p) in themes) {
            assertTrue("$name onAccent on accent", ContrastMath.ratio(p.onAccent, p.accent) >= 4.5)
        }
    }

    @Test
    fun tertiaryTextIsOnlyForDisabledOrHintUseButStillClearsThreeToOne() {
        for ((name, p) in themes) {
            assertTrue("$name tertiary", ContrastMath.ratio(p.textTertiary, p.surface1) >= 3.0)
        }
    }

    @Test
    fun dividersAreDistinguishableFromSurfacesButNotLoud() {
        for ((name, p) in themes) {
            val r = ContrastMath.ratio(p.line, p.background)
            assertTrue("$name line on background %.2f".format(r), r in 1.1..3.0)
        }
    }
}
