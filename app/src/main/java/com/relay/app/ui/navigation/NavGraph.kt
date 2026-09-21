package com.relay.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.relay.app.ui.screens.account.AccountScreen
import com.relay.app.ui.screens.chat.ChatScreen
import com.relay.app.ui.screens.chat.GroupChatScreen
import com.relay.app.ui.screens.contacts.ContactsScreen
import com.relay.app.ui.screens.map.MapScreen
import com.relay.app.ui.screens.map.PinHistoryScreen
import com.relay.app.ui.screens.messages.MessagesScreen
import com.relay.app.ui.screens.qr.QrExchangeScreen
import com.relay.app.ui.theme.Background

sealed class Screen(val route: String) {
    // Top-level tabs (bottom bar visible)
    object Map : Screen("map")
    object Messages : Screen("messages")
    object Account : Screen("account")

    // Detail screens (bottom bar hidden)
    object Contacts : Screen("contacts")
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

/**
 * The whole app UI below the lock screen: a bottom navigation bar with three tabs (Map, Messages,
 * Account) shown only on those top-level screens, and a single NavHost for everything. Chat, QR
 * pairing, contact management and pin history are full-screen detail routes that hide the bar.
 */
@Composable
fun RelayNavGraph(navController: NavHostController, unreadMessages: Int = 0) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = Tab.forRoute(backStackEntry?.destination?.route)

    Scaffold(
        // Screens inside manage their own system-bar insets (the map is full-bleed under the status
        // bar), so the shell contributes only the bottom bar's own height.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Background,
        bottomBar = {
            if (currentTab != null) {
                RelayBottomBar(
                    selected = currentTab,
                    unreadMessages = unreadMessages,
                    onSelect = { navController.navigateToTab(it) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Messages.route,
            // consumeWindowInsets stops the nested screens' own Scaffolds from adding the
            // navigation-bar inset a second time on top of the bar we already sit above.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Screen.Map.route) { MapScreen(navController = navController) }
            composable(Screen.Messages.route) { MessagesScreen(navController = navController) }
            composable(Screen.Account.route) { AccountScreen(navController = navController) }
            composable(Screen.Contacts.route) { ContactsScreen(navController = navController) }
            composable(Screen.PinHistory.route) { PinHistoryScreen(navController = navController) }
            composable(Screen.QrExchange.route) { QrExchangeScreen(navController = navController) }
            composable(
                route = Screen.Chat.ROUTE,
                arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
            ) { entry ->
                ChatScreen(contactId = entry.arguments!!.getLong("contactId"), navController = navController)
            }
            composable(
                route = Screen.GroupChat.ROUTE,
                arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
            ) { entry ->
                GroupChatScreen(groupId = entry.arguments!!.getLong("groupId"), navController = navController)
            }
        }
    }
}
