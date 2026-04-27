package com.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.relay.app.ui.navigation.RelayNavGraph
import com.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RelayTheme {
                val navController = rememberNavController()
                RelayNavGraph(navController = navController)
            }
        }
    }
}
