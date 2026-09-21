package com.relay.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.relay.app.R

/** IBM Plex Sans: everything you read (messages, descriptions, buttons). */
val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** IBM Plex Mono: data only (Relay IDs, coordinates). Not for labels or timestamps. */
val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

/**
 * Bricolage Grotesque (SIL OFL, see licenses/): screen titles and names. One variable font file; each
 * weight is a variation of it at a comfortable optical size, so the file is bundled once.
 */
@OptIn(ExperimentalTextApi::class)
val Bricolage = FontFamily(
    Font(
        R.font.bricolage_grotesque, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.width(100f), FontVariation.opticalSizing(20.sp)),
    ),
    Font(
        R.font.bricolage_grotesque, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.width(100f), FontVariation.opticalSizing(24.sp)),
    ),
    Font(
        R.font.bricolage_grotesque, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(100f), FontVariation.opticalSizing(30.sp)),
    ),
)

/**
 * The type scale. Screens use these roles (`MaterialTheme.typography.titleMedium`...) and never set a
 * font family or size inline. Colours are deliberately not part of the styles: they come from the palette.
 *
 *  - display / title: Bricolage Grotesque (identity)
 *  - body / label: IBM Plex Sans (legibility)
 */
val RelayTypography = Typography(
    // Screen titles ("Messages", "Account")
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    // Large names and section titles
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    // Names in lists, sheet titles
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    // Messages and reading text
    bodyLarge = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    // Buttons, chips, tab labels
    labelLarge = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

/** For Relay IDs and coordinates, the only place monospace is used. */
val RelayDataStyle = TextStyle(fontFamily = IbmPlexMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
