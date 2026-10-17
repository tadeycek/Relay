package com.relay.app.calculator

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.relay.app.R
import kotlin.math.sin

/** Four-second dance of the supplied illustration: sway, bob and a small zoom. No network/video. */
@Composable
fun UnlockDance(onSkip: () -> Unit) {
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { elapsed = (it - start) / 1_000_000_000f }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF5F0)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.unlock_dance), contentDescription = "Dancing illustration",
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.88f).clickable(onClickLabel = "Skip unlock animation", onClick = onSkip).graphicsLayer {
                rotationZ = sin(elapsed * 7f) * 5f
                translationX = sin(elapsed * 7f) * 18f
                translationY = -kotlin.math.abs(sin(elapsed * 7f)) * 28f
                scaleX = 1f + sin(elapsed * 3.5f) * 0.025f
                scaleY = scaleX
            })
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { Text("Skip animation") }
    }
}
