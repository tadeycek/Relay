package com.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.app.ui.theme.TextSecondary

@Composable
fun InitialLoadState(error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (error != null) {
        EmptyState("Couldn't load saved data", error, modifier, "Try again", onRetry)
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading saved data…", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}
