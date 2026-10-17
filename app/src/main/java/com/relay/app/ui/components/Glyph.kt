package com.relay.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.Contact
import com.relay.app.ui.glyph.Corner
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.glyph.GlyphSpec
import com.relay.app.ui.glyph.GlyphState
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

private fun strokeColorFor(state: GlyphState, verified: Color, accent: Color, muted: Color): Color = when (state) {
    GlyphState.VERIFIED -> verified
    GlyphState.SELF -> accent
    GlyphState.UNVERIFIED, GlyphState.NEUTRAL -> muted
}

/** Draws the arcs of [spec] inside the square at [origin] with side [extent]. */
private fun DrawScope.drawGlyphTiles(spec: GlyphSpec, origin: Offset, extent: Float, color: Color) {
    val tile = extent / GlyphSpec.GRID
    val radius = tile / 2f
    val stroke = Stroke(width = tile * 0.3f, cap = StrokeCap.Round)
    for (row in 0 until GlyphSpec.GRID) {
        for (col in 0 until GlyphSpec.GRID) {
            val x = origin.x + col * tile
            val y = origin.y + row * tile
            for (corner in GlyphGenerator.cornersFor(spec.variantAt(row, col))) {
                // Each arc is a quarter circle centred on a tile corner, so neighbouring tiles join up.
                val (cx, cy, start) = when (corner) {
                    Corner.TOP_LEFT -> Triple(x, y, 0f)
                    Corner.TOP_RIGHT -> Triple(x + tile, y, 90f)
                    Corner.BOTTOM_RIGHT -> Triple(x + tile, y + tile, 180f)
                    Corner.BOTTOM_LEFT -> Triple(x, y + tile, 270f)
                }
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = stroke,
                )
            }
        }
    }
}

/**
 * A contact's avatar, drawn from their key. Verified people are drawn in verdigris; someone who has not
 * been scanned in person gets grey strokes inside a dashed frame; you are drawn in the accent colour.
 * Colour is never the only signal: pass a [description] that names the state for screen readers.
 */
@Composable
fun Glyph(
    seed: String,
    state: GlyphState,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    description: String? = null,
) {
    val spec = remember(seed) { GlyphGenerator.fromKey(seed) }
    val color = strokeColorFor(state, Verified, Accent, TextSecondary)
    val background = Surface3
    val frameColor = TextSecondary
    Canvas(
        modifier = modifier
            .size(size)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        drawRect(background)
        val pad = this.size.minDimension * 0.16f
        drawGlyphTiles(spec, Offset(pad, pad), this.size.minDimension - 2 * pad, color)
        if (state == GlyphState.UNVERIFIED) {
            val w = 1.5.dp.toPx()
            drawRect(
                color = frameColor,
                topLeft = Offset(w / 2, w / 2),
                size = Size(this.size.width - w, this.size.height - w),
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2))),
            )
        }
    }
}

/** A group's avatar: up to four members' glyphs in a two-by-two. */
@Composable
fun GroupGlyph(
    memberSeeds: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    description: String? = null,
) {
    val specs = remember(memberSeeds) { memberSeeds.take(4).map { GlyphGenerator.fromKey(it) } }
    val color = TextSecondary
    val background = Surface3
    Canvas(
        modifier = modifier
            .size(size)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        drawRect(background)
        val gap = this.size.minDimension * 0.08f
        val half = (this.size.minDimension - 3 * gap) / 2f
        specs.forEachIndexed { i, spec ->
            val origin = Offset(gap + (i % 2) * (half + gap), gap + (i / 2) * (half + gap))
            drawGlyphTiles(spec, origin, half, color)
        }
    }
}

/** The glyph for a saved contact, with the trust state and a screen-reader description filled in. */
@Composable
fun ContactGlyph(contact: Contact, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val state = GlyphState.forContact(hasNostrKey = contact.nostrPubkey != null, verifiedInPerson = contact.qrVerified)
    val trust = when (state) {
        GlyphState.VERIFIED -> "verified in person"
        GlyphState.UNVERIFIED -> "not verified"
        else -> "no verification"
    }
    Glyph(
        seed = GlyphGenerator.seedFor(contact.nostrPubkey, contact.phone.takeIf { contact.hasPhone }, contact.name),
        state = state,
        modifier = modifier,
        size = size,
        description = "${contact.name}, $trust",
    )
}
