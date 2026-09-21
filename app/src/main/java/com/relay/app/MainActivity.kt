package com.relay.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.ui.lock.AppLockScreen
import com.relay.app.ui.lock.deviceSupportsAppLock
import com.relay.app.ui.lock.promptAppUnlock
import com.relay.app.ui.navigation.RelayNavGraph
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.RelayTheme
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    // Note: read once per composition of the lock gate; toggling "App lock" in Settings while
    // Relay is already in the foreground takes effect on the next cold start / resume-from-background,
    // not instantly — an accepted simplification rather than plumbing an observable prefs flow.
    private val isUnlocked = mutableStateOf(false)

    // Set from the "key changed" security notification's PendingIntent (see
    // SmsReceiver.showKeyChangeNotification) — a State rather than reading `intent` directly so
    // it also works when the activity is already running and only gets onNewIntent, not onCreate.
    private val pendingOpenContacts = mutableStateOf(false)

    // Resolved contact id to jump straight to a chat with, from an ACTION_SENDTO intent (e.g.
    // "Message" from Contacts/Phone, or Relay being the target of a share-to-sms) — see
    // handleSendToIntent. Null when there's nothing pending.
    private val pendingChatContactId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingOpenContacts.value = intent?.getBooleanExtra("open_contacts", false) == true
        handleSendToIntent(intent)
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
                    SmsPermissionGate {
                        val navController = rememberNavController()
                        RelayNavGraph(navController = navController)

                        val shouldOpenContacts by pendingOpenContacts
                        LaunchedEffect(shouldOpenContacts) {
                            if (shouldOpenContacts) {
                                navController.navigate(Screen.Contacts.route)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_contacts", false)) {
            pendingOpenContacts.value = true
        }
        handleSendToIntent(intent)
    }

    /**
     * Handles ACTION_SENDTO for sms/smsto/mms/mmsto (see AndroidManifest.xml) — required for
     * default-SMS-app eligibility, and also what makes "Message this number" from other apps
     * actually work. Resolves/creates the contact for the target phone number and navigates
     * straight to that chat.
     */
    private fun handleSendToIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SENDTO) return
        val data = intent.data ?: return
        if (data.scheme !in setOf("sms", "smsto", "mms", "mmsto")) return
        val phone = data.schemeSpecificPart?.substringBefore("?")?.trim()
        if (phone.isNullOrEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = RelayDbHelper(applicationContext)
            val contact = ContactRepository(db).findOrCreateByPhoneSync(phone)
            pendingChatContactId.value = contact.id
        }
    }

    override fun onStop() {
        super.onStop()
        isUnlocked.value = false
    }
}

/**
 * Relay's entire reason for existing depends on SEND_SMS/RECEIVE_SMS/READ_SMS (and
 * RECEIVE_MMS/READ_MMS) — all dangerous runtime permissions, denied by default. Nothing
 * elsewhere in the app ever asked for them; without this gate the app would silently fail to
 * send or receive a single real message on a fresh install. Requests once on launch and blocks
 * the rest of the UI (mirroring the AppLockScreen gate in MainActivity) until granted — a user
 * who permanently denies can retry via the button, which re-triggers the system prompt (or, once
 * permanently denied at the OS level, at least makes it obvious why nothing works instead of
 * failing silently).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun SmsPermissionGate(content: @Composable () -> Unit) {
    val smsPermissions = remember {
        listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_MMS,
            // No public constant for READ_MMS in the SDK 36 android.Manifest.permission class.
            "android.permission.READ_MMS",
        )
    }
    val permState = rememberMultiplePermissionsState(smsPermissions)

    LaunchedEffect(Unit) {
        if (!permState.allPermissionsGranted) permState.launchMultiplePermissionRequest()
    }

    if (permState.allPermissionsGranted) {
        content()
    } else {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    "Relay needs SMS/MMS permissions to send and receive messages.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { permState.launchMultiplePermissionRequest() }) {
                    Text("Grant permissions")
                }
            }
        }
    }
}
