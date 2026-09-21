package com.relay.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelayShapeTokens
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

private val ButtonPadding = PaddingValues(horizontal = RelaySpacing.xl, vertical = RelaySpacing.md)

/** The main action on a screen. One per screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RelayShapeTokens.control,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent, contentColor = OnAccent,
            disabledContainerColor = Surface2, disabledContentColor = TextSecondary,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A secondary action next to or below a [PrimaryButton]. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RelayShapeTokens.control,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary, disabledContentColor = TextSecondary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Destructive actions (delete, block). Always paired with a confirmation. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RelayShapeTokens.control,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Background),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

enum class ChipKind { VERIFIED, NEUTRAL, WARNING, DANGER }

/**
 * A small status pill: a dot plus a word. Colour is never the only signal: the word always says what
 * the state is ("Verified", "Not verified", "Waiting to send").
 */
@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val tone: Color = when (kind) {
        ChipKind.VERIFIED -> Verified
        ChipKind.NEUTRAL -> TextSecondary
        ChipKind.WARNING -> TextPrimary
        ChipKind.DANGER -> Danger
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = RelaySpacing.sm + 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.size(6.dp).clip(CircleShape).background(tone))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = tone)
    }
}

/** An empty screen is an invitation to act: say what belongs here and offer the next step. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = RelaySpacing.xxl, vertical = RelaySpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(RelaySpacing.sm))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(RelaySpacing.xl))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * Confirm before anything irreversible. The confirm button repeats the verb ("Delete contact"), never
 * "OK", and turns red when [destructive].
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RelayShapeTokens.sheet,
        containerColor = Surface1,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge, color = if (destructive) Danger else Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            }
        },
    )
}

/** Modal bottom sheet with the app's shape and surface; content gets standard gutters and clears the nav bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayBottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RelayShapeTokens.sheet,
        containerColor = Surface1,
        contentColor = TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = RelaySpacing.gutter)
                .padding(bottom = RelaySpacing.xl),
        ) { content() }
    }
}
