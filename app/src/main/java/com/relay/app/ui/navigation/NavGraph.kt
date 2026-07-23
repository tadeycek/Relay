package com.relay.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.relay.app.ui.screens.chat.ChatScreen
import com.relay.app.ui.screens.chat.GroupChatScreen
import com.relay.app.ui.screens.contacts.ContactsScreen
import com.relay.app.ui.screens.map.MapScreen
import com.relay.app.ui.screens.map.PinHistoryScreen
import com.relay.app.ui.screens.qr.QrExchangeScreen
import com.relay.app.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Map : Screen("map")
    object Contacts : Screen("contacts")
    object Settings : Screen("settings")
    object PinHistory : Screen("pin_history")
    object QrExchange : Screen("qr_exchange")
    object Chat : Screen("chat/{contactId}") {
        const val ROUTE = "chat/{contactId}"
        fun routeFor(id: Long) = "chat/$id"
    }
    object GroupChat : Screen("group_chat/{groupId}") {
        const val ROUTE = "group_chat/{groupId}"
        fun routeFor(id: Long) = "group_chat/$id"
    }
}

@Composable
fun RelayNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Map.route,
    ) {
        composable(Screen.Map.route) {
            MapScreen(navController = navController)
        }
        composable(Screen.Contacts.route) {
            ContactsScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.PinHistory.route) {
            PinHistoryScreen(navController = navController)
        }
        composable(Screen.QrExchange.route) {
            QrExchangeScreen(navController = navController)
        }
        composable(
            route = Screen.Chat.ROUTE,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments!!.getLong("contactId")
            ChatScreen(contactId = contactId, navController = navController)
        }
        composable(
            route = Screen.GroupChat.ROUTE,
            arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments!!.getLong("groupId")
            GroupChatScreen(groupId = groupId, navController = navController)
        }
    }
}
