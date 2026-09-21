package com.relay.app.ui.theme

import kotlin.math.pow

/**
 * The raw colour values of both themes as plain ARGB longs (no Compose types), so the accessibility
 * maths in [ContrastMath] can be unit-tested on the JVM. [Palette] in Color.kt turns these into Colors.
 */
object PaletteSpec {

    data class Values(
        val background: Long,
        val surface1: Long,
        val surface2: Long,
        val surface3: Long,
        val line: Long,
        val textPrimary: Long,
        val textSecondary: Long,
        val textTertiary: Long,
        val accent: Long,
        val onAccent: Long,
        val verified: Long,
        val danger: Long,
    )

    /** Ink background (true black, kept for OLED), graphite steps, bone text. */
    val dark = Values(
        background = 0xFF000000,
        surface1 = 0xFF0E0F10,
        surface2 = 0xFF17191B,
        surface3 = 0xFF202326,
        line = 0xFF26292C,
        textPrimary = 0xFFECEAE4,
        textSecondary = 0xFF8B9094,
        textTertiary = 0xFF6E7378,
        accent = 0xFFECEAE4,
        onAccent = 0xFF000000,
        verified = 0xFF6FB39D, // verdigris: only for "verified" and "connected"
        danger = 0xFFE5604D,
    )

    val light = Values(
        background = 0xFFF6F7F8,
        surface1 = 0xFFFFFFFF,
        surface2 = 0xFFECEEF0,
        surface3 = 0xFFE1E4E7,
        line = 0xFFD5D9DD,
        textPrimary = 0xFF15181A,
        textSecondary = 0xFF5C6266,
        textTertiary = 0xFF7C8286,
        accent = 0xFF15181A,
        onAccent = 0xFFF6F7F8,
        verified = 0xFF2A7562, // darkened from the first draft: 2F7F6B was 4.49:1, just under AA on the background
        danger = 0xFFC0392B,
    )
}

/** WCAG 2.x contrast ratio maths. */
object ContrastMath {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** 1.0 (identical) to 21.0 (black on white). */
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
