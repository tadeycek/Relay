package com.relay.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelayShapeTokens
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.TextTertiary

/**
 * The one list row. Flat (no card), 56 dp minimum so it is comfortable to tap, with an optional leading
 * element (a glyph or icon), supporting text and a trailing element. Every settings row, contact row and
 * conversation row is built from this instead of hand-rolled Rows.
 */
@Composable
fun ListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    maxSupportingLines: Int = 2,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RelaySpacing.md),
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextTertiary,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = maxSupportingLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Hairline between rows; [inset] lines it up with the text after a leading avatar. */
@Composable
fun RowDivider(inset: Dp = RelaySpacing.gutter) {
    HorizontalDivider(modifier = Modifier.padding(start = inset), thickness = 1.dp, color = Border)
}

/** Sentence-case heading above a group of rows. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RelaySpacing.gutter, end = RelaySpacing.gutter, top = RelaySpacing.xl, bottom = RelaySpacing.xs),
    )
}

/** A row that navigates somewhere (an Account sub-page): headline, optional summary, chevron. */
@Composable
fun LinkRow(headline: String, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    ListRow(
        headline = headline,
        supporting = supporting,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
            )
        },
    )
}

/** A row with a switch. The whole row is the touch target and the switch is only its indicator. */
@Composable
fun ToggleRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    ListRow(
        headline = headline,
        supporting = supporting,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnAccent,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = Surface2,
                    uncheckedBorderColor = Border,
                    disabledCheckedTrackColor = Border,
                    disabledUncheckedTrackColor = Surface2,
                ),
            )
        },
    )
}

/**
 * Pick one of two to four short options, all visible at once. Replaces dropdown menus, which hid the
 * choices and clipped their own text.
 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RelayShapeTokens.control)
            .background(Surface2)
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val fill by animateColorAsState(if (isSelected) Accent else Color.Transparent, label = "segmentFill")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RelayShapeTokens.control)
                    .background(fill)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = RelaySpacing.md, vertical = RelaySpacing.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) OnAccent else TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A headline with a segmented control below it, for settings that pick one of a few values. */
@Composable
fun <T> ChoiceRow(
    headline: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
    ) {
        Text(text = headline, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        if (supporting != null) {
            Text(text = supporting, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        SegmentedControl(options = options, selected = selected, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
    }
}
