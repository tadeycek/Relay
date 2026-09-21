package com.relay.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.TextSecondary

@Composable
fun NavIconButton(
    icon: ImageVector,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val shape = RectangleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Border, shape)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}
