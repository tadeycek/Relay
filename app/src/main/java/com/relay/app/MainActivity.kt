package com.relay.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import com.relay.app.media.LocalChatMediaPicker
import com.relay.app.media.rememberChatMediaPicker
import com.relay.app.media.LocalProfilePhotoPicker
import com.relay.app.media.rememberProfilePhotoPicker
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.relay.app.ui.theme.ThemeMode
import com.relay.app.util.RelayPreferences
import com.relay.app.calculator.CalculatorScreen
import com.relay.app.messaging.ActiveChat
import androidx.lifecycle.ViewModelProvider
import com.relay.app.calculator.CalculatorSession
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    private val calculatorUnlocked = mutableStateOf(false)
    private val calculatorSession by lazy { ViewModelProvider(this)[CalculatorSession::class.java] }

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

    // A tap the system routed to Relay via the manifest's NDEF intent-filter rather than reader mode
    // (see NfcPairing.handleNdefIntent) — jump to the scan screen so the code it carried is not lost.
    private val pendingOpenScan = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        // Load the saved Dark / Light / System choice before the first frame so there is no flash.
        ThemeController.load(applicationContext)
        PairingCoordinator.restore(applicationContext)
        pendingOpenContacts.value = intent?.getBooleanExtra("open_contacts", false) == true
        handleOpenChatIntent(intent)
        pendingOpenScan.value = com.relay.app.nfc.NfcPairing.handleNdefIntent(intent)
        setContent {
            val deadline = calculatorSession.deadline
            LaunchedEffect(deadline) {
                while (calculatorSession.isActive()) delay(calculatorSession.remainingMs().coerceAtLeast(1L))
                calculatorSession.expireIfNeeded()
                if (!calculatorSession.isActive()) closePrivateScreens()
            }
            val privateScreenState = rememberSaveableStateHolder()
            val mediaPicker = rememberChatMediaPicker(
                onLaunch = { calculatorSession.pickerOpen = true },
                onReturn = { calculatorSession.pickerOpen = false },
            )
            val profilePhotoPicker = rememberProfilePhotoPicker(
                onLaunch = { calculatorSession.pickerOpen = true },
                onReturn = { calculatorSession.pickerOpen = false },
            )
            val messagingDark = when (ThemeController.mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SideEffect { setDarkSystemBars(!calculatorUnlocked.value || messagingDark) }
            if (!calculatorUnlocked.value) {
                CalculatorScreen(onUnlock = {
                    calculatorSession.unlock()
                    calculatorUnlocked.value = true
                })
            } else {
            privateScreenState.SaveableStateProvider("private_screens") {
            CompositionLocalProvider(LocalChatMediaPicker provides mediaPicker,
                LocalProfilePhotoPicker provides profilePhotoPicker) {
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

                    val shouldOpenScan by pendingOpenScan
                    LaunchedEffect(shouldOpenScan) {
                        if (shouldOpenScan) {
                            navController.navigate(Screen.QrExchange.route)
                            pendingOpenScan.value = false
                        }
                    }
                }
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
        if (com.relay.app.nfc.NfcPairing.handleNdefIntent(intent)) pendingOpenScan.value = true
    }

    /** Tap on a new-message notification (see MessageNotifier): jump straight to that chat. */
    private fun handleOpenChatIntent(intent: Intent?) {
        val id = intent?.getLongExtra(MessageNotifier.EXTRA_OPEN_CHAT, -1L) ?: -1L
        if (id > 0) pendingChatContactId.value = id
    }

    override fun onStart() {
        super.onStart()
        calculatorSession.expireIfNeeded()
        calculatorUnlocked.value = calculatorSession.isActive()
        // The UI itself keeps messaging alive; don't show a connection-service notice on startup.
        ConnectionService.onAppForeground(this)
    }

    override fun onResume() {
        super.onResume()
        // A quick Home/return can pause and resume without ever reaching onStop/onStart.
        calculatorSession.expireIfNeeded()
        calculatorUnlocked.value = calculatorSession.isActive()
        ConnectionService.onAppForeground(this)
    }

    private fun closePrivateScreens() {
        calculatorUnlocked.value = false
        isUnlocked.value = false
        ActiveChat.contactId = -1L
        ActiveChat.groupId = -1L
    }

    private fun setDarkSystemBars(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !calculatorUnlocked.value) setDarkSystemBars(true)
    }

    override fun onUserLeaveHint() {
        if (!calculatorSession.keepChatForPicker()) closePrivateScreens()
        super.onUserLeaveHint()
    }

    override fun onStop() {
        if (!calculatorSession.keepChatForPicker()) closePrivateScreens()
        // Only start when leaving the visible UI, not for permission dialogs or rotation.
        if (!isChangingConfigurations) ConnectionService.onAppBackground(this)
        super.onStop()
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
