package com.relay.app.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.relay.app.ui.screens.settings.SettingsScreen

/** Placeholder for step 1: the profile card + embedded settings replace this in step 3. */
@Composable
fun AccountScreen(navController: NavController) {
    SettingsScreen(navController = navController)
}
