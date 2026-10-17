package com.relay.app.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.screens.settings.AccountPage
import com.relay.app.ui.screens.settings.AccountPageContent
import com.relay.app.ui.screens.settings.SettingsViewModel
import com.relay.app.ui.theme.Background

/** One settings page opened from the Account tab, with a back arrow. */
@Composable
fun AccountPageScreen(page: AccountPage, navController: NavController) {
    val vm: SettingsViewModel = viewModel()

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshOrbotInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        RelayTopBar(title = page.title, onBack = { navController.popBackStack() })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AccountPageContent(page, vm)
        }
    }
}
