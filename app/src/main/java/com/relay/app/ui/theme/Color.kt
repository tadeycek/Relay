package com.relay.app.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome terminal palette: pure black background, white as the only accent.
// Anywhere Accent is used as a *fill* (buttons, FABs, selected avatars, sent-message
// bubbles), the foreground on top of it must be OnAccent (black), not white.
val Accent = Color(0xFFFFFFFF)
val OnAccent = Color(0xFF000000)
val AccentDim = Color(0x26FFFFFF)
val AccentGlow = Color(0x80FFFFFF)
val Background = Color(0xFF000000)
val Surface1 = Color(0xFF0A0A0A)
val Surface2 = Color(0xFF141414)
val Surface3 = Color(0xFF1E1E1E)
val Border = Color(0xFF262626)
val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFF707070)
val TextTertiary = Color(0xFF4A4A4A)
