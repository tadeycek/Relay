package com.relay.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.DeliveryState
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

/**
 * Where an outgoing message is: waiting (clock), sent (one tick), read (two verdigris ticks) or failed
 * (red). The shape differs for each state, so colour is never the only signal.
 */
@Composable
fun DeliveryTick(readAt: Long?, deliveryState: Int, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val (icon, tint, description) = when {
        readAt != null -> Triple(Icons.Filled.DoneAll, Verified, "Read")
        deliveryState == DeliveryState.QUEUED -> Triple(Icons.Outlined.Schedule, TextSecondary, "Waiting to send")
        deliveryState == DeliveryState.FAILED -> Triple(Icons.Outlined.ErrorOutline, Danger, "Failed to send")
        else -> Triple(Icons.Filled.Done, TextSecondary, "Sent")
    }
    Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = modifier.size(size))
}
