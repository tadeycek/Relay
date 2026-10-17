package com.relay.app.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.relay.app.util.RelayPreferences

enum class ThemeMode(val prefsKey: String) {
    SYSTEM(RelayPreferences.THEME_SYSTEM),
    DARK(RelayPreferences.THEME_DARK),
    LIGHT(RelayPreferences.THEME_LIGHT);

    companion object {
        fun fromPrefs(value: String?): ThemeMode = entries.firstOrNull { it.prefsKey == value } ?: SYSTEM
    }
}

/**
 * The live Dark / Light / System choice. It is Compose state, so changing it in Settings re-themes the
 * whole app immediately; it is persisted through [RelayPreferences] by the Settings screen.
 */
object ThemeController {
    var mode by mutableStateOf(ThemeMode.SYSTEM)

    fun load(context: Context) {
        mode = ThemeMode.fromPrefs(RelayPreferences(context).theme)
    }

    fun set(context: Context, newMode: ThemeMode) {
        RelayPreferences(context).theme = newMode.prefsKey
        mode = newMode
    }
}

private fun colorScheme(p: Palette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentDim, onPrimaryContainer = p.textPrimary,
        secondary = p.surface2, onSecondary = p.textPrimary,
        background = p.background, onBackground = p.textPrimary,
        surface = p.surface1, onSurface = p.textPrimary,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textSecondary,
        surfaceContainer = p.surface2, surfaceContainerHigh = p.surface3,
        outline = p.line, outlineVariant = p.line,
        error = p.danger, onError = p.background,
    )
} else {
    lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentDim, onPrimaryContainer = p.textPrimary,
        secondary = p.surface2, onSecondary = p.textPrimary,
        background = p.background, onBackground = p.textPrimary,
        surface = p.surface1, onSurface = p.textPrimary,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textSecondary,
        surfaceContainer = p.surface2, surfaceContainerHigh = p.surface3,
        outline = p.line, outlineVariant = p.line,
        error = p.danger, onError = p.background,
    )
}

/**
 * Shape hierarchy, not one radius for everything: controls 10, message bubbles 14, sheets and dialogs
 * 20. Avatar glyphs are deliberately square and set their own shape.
 */
object RelayShapeTokens {
    val control = RoundedCornerShape(10.dp)
    val bubble = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(20.dp)
}

private val RelayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RelayShapeTokens.control,
    medium = RelayShapeTokens.bubble,
    large = RelayShapeTokens.sheet,
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeController.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette

    // Edge-to-edge draws under the system bars; their icons must contrast with *our* theme, which can
    // differ from the phone's own dark/light mode.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme(palette),
            typography = RelayTypography,
            shapes = RelayShapes,
            content = content,
        )
    }
}
