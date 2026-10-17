package com.relay.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for the current theme (dark or light), built from [PaletteSpec].
 *
 * Read them through the names below (`Accent`, `Surface1`, `TextSecondary`, `Verified`...) inside any
 * composable: each is a theme-aware getter, so every screen follows the Dark / Light / System setting
 * without knowing which one is active. Never hard-code a Color in a screen.
 */
@Immutable
class Palette(
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val verified: Color,
    val danger: Color,
    val isDark: Boolean,
) {
    val accentDim: Color get() = accent.copy(alpha = 0.15f)
    val accentGlow: Color get() = accent.copy(alpha = 0.5f)
    /** Sent bubbles use the accent fill; received ones sit on the raised surface. */
    val sentBubble: Color get() = accent
    val onSentBubble: Color get() = onAccent
    val receivedBubble: Color get() = surface2
}

private fun PaletteSpec.Values.toPalette(isDark: Boolean) = Palette(
    background = Color(background),
    surface1 = Color(surface1),
    surface2 = Color(surface2),
    surface3 = Color(surface3),
    line = Color(line),
    textPrimary = Color(textPrimary),
    textSecondary = Color(textSecondary),
    textTertiary = Color(textTertiary),
    accent = Color(accent),
    onAccent = Color(onAccent),
    verified = Color(verified),
    danger = Color(danger),
    isDark = isDark,
)

val DarkPalette = PaletteSpec.dark.toPalette(isDark = true)
val LightPalette = PaletteSpec.light.toPalette(isDark = false)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Theme-aware getters under the names the existing screens already use.
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
val AccentDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accentDim
val AccentGlow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accentGlow
val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface1
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val Surface3: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface3
val Border: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary

/** Verified in person / connected / success. The only accent hue, used only when it means something. */
val Verified: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.verified

/** Destructive actions and failures. */
val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
