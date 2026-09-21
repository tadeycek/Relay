package com.relay.app.ui.screens.messages

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.relay.app.ui.screens.contacts.ContactsScreen

/** Placeholder for step 1: the conversation list replaces this in the next step. */
@Composable
fun MessagesScreen(navController: NavController) {
    ContactsScreen(navController = navController)
}
