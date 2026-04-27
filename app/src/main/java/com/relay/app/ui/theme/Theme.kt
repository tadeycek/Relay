package com.relay.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RelayColors = darkColorScheme(
    primary = Accent,
    onPrimary = Background,
    primaryContainer = AccentDim,
    onPrimaryContainer = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = Border,
    secondary = Surface2,
    onSecondary = TextPrimary,
)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RelayColors,
        typography = RelayTypography,
        content = content,
    )
}
