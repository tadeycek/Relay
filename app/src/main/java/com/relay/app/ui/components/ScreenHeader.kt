package com.relay.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary

/**
 * Big left-aligned title for the three top-level tabs (People, Messages, Account). The tab bar already
 * says where you are, so there is no back arrow; actions sit on the right.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = RelaySpacing.gutter, end = RelaySpacing.sm, top = RelaySpacing.md, bottom = RelaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
