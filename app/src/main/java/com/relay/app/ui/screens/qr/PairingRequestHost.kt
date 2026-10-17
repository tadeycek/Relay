package com.relay.app.ui.screens.qr

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.relay.app.pairing.PairingCoordinator
import com.relay.app.pairing.PairingEvent
import com.relay.app.ui.components.ConfirmDialog

/**
 * Lives above the whole app, so the question reaches the second person on whatever screen they are on:
 * "X added you. Add them back?" A yes here, together with the first person's yes, verifies both sides.
 */
@Composable
fun PairingRequestHost() {
    val context = LocalContext.current
    val incoming by PairingCoordinator.incoming.collectAsState()

    LaunchedEffect(Unit) {
        PairingCoordinator.events.collect { event ->
            val text = when (event) {
                is PairingEvent.Verified -> "You and ${event.name} are verified"
                is PairingEvent.TimedOut -> "${event.name} was not added: the answer took too long"
                is PairingEvent.NeedsKeyReview -> "${event.name} has a different key on file. Review it in People"
                is PairingEvent.Cancelled -> null
            }
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    incoming?.let { request ->
        val name = request.name.ifBlank { "Someone" }
        ConfirmDialog(
            title = "$name added you",
            message = "Add them back to verify each other. Only do this if they are with you right now. " +
                "If you don't answer in a few minutes, neither of you is kept.",
            confirmLabel = "Add $name",
            dismissLabel = "No",
            onConfirm = { PairingCoordinator.acceptIncoming(context) },
            onDismiss = { PairingCoordinator.declineIncoming() },
        )
    }
}
