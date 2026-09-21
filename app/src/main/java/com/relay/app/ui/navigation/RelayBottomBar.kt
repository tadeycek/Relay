package com.relay.app.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.TextSecondary

/** The three top-level destinations, left to right. */
enum class Tab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    MAP(Screen.Map.route, "MAP", Icons.Filled.Map, Icons.Outlined.Map),
    MESSAGES(Screen.Messages.route, "MESSAGES", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    ACCOUNT(Screen.Account.route, "ACCOUNT", Icons.Filled.Person, Icons.Outlined.PersonOutline);

    companion object {
        /** The tab that owns [route], or null for detail screens (chat, QR, contacts...), where the bar is hidden. */
        fun forRoute(route: String?): Tab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Switches tabs the standard way: each tab keeps its own back stack and scroll/UI state, so leaving
 * a tab and coming back restores where you were; tapping the active tab again does nothing extra
 * (launchSingleTop) rather than stacking a copy.
 */
fun NavController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun RelayBottomBar(
    selected: Tab,
    unreadMessages: Int,
    onSelect: (Tab) -> Unit,
) {
    NavigationBar(
        containerColor = Surface1,
        tonalElevation = 0.dp,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    val icon: @Composable () -> Unit = {
                        Icon(
                            imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    if (tab == Tab.MESSAGES && unreadMessages > 0) {
                        BadgedBox(badge = {
                            Badge(containerColor = Accent) {
                                Text(
                                    text = if (unreadMessages > 99) "99+" else unreadMessages.toString(),
                                    fontFamily = IbmPlexMono,
                                    fontSize = 10.sp,
                                )
                            }
                        }) { icon() }
                    } else {
                        icon()
                    }
                },
                label = { Text(tab.label, fontFamily = IbmPlexMono, fontSize = 10.sp, letterSpacing = 0.5.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Border,
                ),
            )
        }
    }
}
