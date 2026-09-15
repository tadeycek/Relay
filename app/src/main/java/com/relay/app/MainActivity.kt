package com.relay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.relay.app.ui.lock.AppLockScreen
import com.relay.app.ui.lock.deviceSupportsAppLock
import com.relay.app.ui.lock.promptAppUnlock
import com.relay.app.ui.navigation.RelayNavGraph
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.RelayTheme
import com.relay.app.util.RelayPreferences

class MainActivity : FragmentActivity() {

    // Note: read once per composition of the lock gate; toggling "App lock" in Settings while
    // Relay is already in the foreground takes effect on the next cold start / resume-from-background,
    // not instantly — an accepted simplification rather than plumbing an observable prefs flow.
    private val isUnlocked = mutableStateOf(false)

    // Set from the "key changed" security notification's PendingIntent (see
    // SmsReceiver.showKeyChangeNotification) — a State rather than reading `intent` directly so
    // it also works when the activity is already running and only gets onNewIntent, not onCreate.
    private val pendingOpenContacts = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingOpenContacts.value = intent?.getBooleanExtra("open_contacts", false) == true
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
                    val navController = rememberNavController()
                    RelayNavGraph(navController = navController)

                    val shouldOpenContacts by pendingOpenContacts
                    LaunchedEffect(shouldOpenContacts) {
                        if (shouldOpenContacts) {
                            navController.navigate(Screen.Contacts.route)
                            pendingOpenContacts.value = false
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
    }

    override fun onStop() {
        super.onStop()
        isUnlocked.value = false
    }
}
