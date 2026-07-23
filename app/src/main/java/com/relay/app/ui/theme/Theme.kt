package com.relay.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val RelayColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
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

// Terminal look: no rounded corners anywhere, including Material3 defaults
// (dialogs, buttons, text fields, menus) that aren't given an explicit shape.
// Shapes() requires CornerBasedShape, not the generic Shape interface RectangleShape
// implements, so a zero-radius RoundedCornerShape is used instead (visually identical).
private val SquareCorners = RoundedCornerShape(0.dp)
private val RelayShapes = Shapes(
    extraSmall = SquareCorners,
    small = SquareCorners,
    medium = SquareCorners,
    large = SquareCorners,
    extraLarge = SquareCorners,
)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RelayColors,
        typography = RelayTypography,
        shapes = RelayShapes,
        content = content,
    )
}
