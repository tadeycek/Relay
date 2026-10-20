package com.relay.app.calculator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.relay.app.R

/** A local three-second presentation after PIN acceptance; transport work never waits for it. */
@Composable
fun UnlockLoadingScreen(slot: Int) {
    val scene = when (slot) {
        1 -> R.drawable.loading_scene_2
        2 -> R.drawable.loading_scene_3
        3 -> R.drawable.loading_scene_4
        4 -> R.drawable.loading_scene_5
        else -> R.drawable.loading_scene_1
    }
    BackHandler { /* The session's monotonic deadline ends this screen. */ }
    Box(Modifier.fillMaxSize().background(Color(0xFF101820)), contentAlignment = Alignment.Center) {
        Image(painterResource(scene), contentDescription = "Loading screen ${slot + 1}",
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Text("Loading…", color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(28.dp))
    }
}
