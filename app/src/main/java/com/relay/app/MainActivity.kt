package com.relay.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.relay.app.messaging.ConnectionService
import com.relay.app.messaging.MessageNotifier
import com.relay.app.ui.lock.AppLockScreen
import com.relay.app.ui.lock.deviceSupportsAppLock
import com.relay.app.ui.lock.promptAppUnlock
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.relay.app.pairing.PairingCoordinator
import com.relay.app.ui.navigation.RelayNavGraph
import com.relay.app.ui.screens.qr.PairingRequestHost
import com.relay.app.ui.screens.messages.UnreadViewModel
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.navigation.Tab
import com.relay.app.ui.navigation.navigateToTab
import com.relay.app.ui.theme.RelayTheme
import com.relay.app.ui.theme.ThemeController
import com.relay.app.util.RelayPreferences

class MainActivity : FragmentActivity() {

    // Note: read once per composition of the lock gate; toggling "App lock" in Settings while
    // Relay is already in the foreground takes effect on the next cold start / resume-from-background,
    // not instantly — an accepted simplification rather than plumbing an observable prefs flow.
    private val isUnlocked = mutableStateOf(false)

    // Set from the "key changed" security notification's PendingIntent (see
    // SecurityNotifier.showKeyChange) — a State rather than reading `intent` directly so
    // it also works when the activity is already running and only gets onNewIntent, not onCreate.
    private val pendingOpenContacts = mutableStateOf(false)

    // Contact id to jump straight to a chat with, from a new-message notification (see
    // MessageNotifier). Null when there's nothing pending.
    private val pendingChatContactId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Load the saved Dark / Light / System choice before the first frame so there is no flash.
        ThemeController.load(applicationContext)
        PairingCoordinator.restore(applicationContext)
        pendingOpenContacts.value = intent?.getBooleanExtra("open_contacts", false) == true
        handleOpenChatIntent(intent)
        setContent {
            RelayTheme {
                val prefs = remember { RelayPreferences(applicationContext) }
                val locked = remember { prefs.appLock && deviceSupportsAppLock(this@MainActivity) }
                val unlocked by isUnlocked

                LaunchedEffect(locked, unlocked) {
                    if (locked && !unlocked) promptAppUnlock(this@MainActivity) { isUnlocked.value = true }
                }

                if (locked && !unlocked) {
                    AppLockScreen(onUnlockClick = {
                        promptAppUnlock(this@MainActivity) { isUnlocked.value = true }
                    })
                } else {
                    NotificationPermissionRequest(prefs)

                    val navController = rememberNavController()
                    val unreadVm: UnreadViewModel = viewModel()
                    val unreadTotal by unreadVm.total.collectAsState()
                    RelayNavGraph(navController = navController, unreadMessages = unreadTotal)
                    PairingRequestHost()

                    val shouldOpenContacts by pendingOpenContacts
                    LaunchedEffect(shouldOpenContacts) {
                        if (shouldOpenContacts) {
                            navController.navigateToTab(Tab.PEOPLE)
                            pendingOpenContacts.value = false
                        }
                    }

                    val chatContactId by pendingChatContactId
                    LaunchedEffect(chatContactId) {
                        chatContactId?.let {
                            navController.navigate(Screen.Chat.routeFor(it))
                            pendingChatContactId.value = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_contacts", false)) {
            pendingOpenContacts.value = true
        }
        handleOpenChatIntent(intent)
    }

    /** Tap on a new-message notification (see MessageNotifier): jump straight to that chat. */
    private fun handleOpenChatIntent(intent: Intent?) {
        val id = intent?.getLongExtra(MessageNotifier.EXTRA_OPEN_CHAT, -1L) ?: -1L
        if (id > 0) pendingChatContactId.value = id
    }

    override fun onStart() {
        super.onStart()
        // The activity is visible, which is when Android allows starting a foreground service.
        if (RelayPreferences(applicationContext).backgroundConnection) ConnectionService.start(this)
    }

    override fun onStop() {
        super.onStop()
        isUnlocked.value = false
    }
}

/**
 * Asks once (Android 13+) for permission to show notifications. Without it new-message and
 * location-request notifications are silently skipped, so background delivery would look broken.
 * Never blocks the UI (unlike the SMS-permission gate this replaced): Relay no longer needs any
 * SMS/MMS permissions at all. Asked only once per install so a user who says no is not nagged;
 * they can still enable notifications later in system settings.
 */
@androidx.compose.runtime.Composable
private fun NotificationPermissionRequest(prefs: RelayPreferences) {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!prefs.askedNotificationPermission) {
            prefs.askedNotificationPermission = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
