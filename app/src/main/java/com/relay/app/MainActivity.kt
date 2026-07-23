package com.relay.app

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
import com.relay.app.ui.theme.RelayTheme
import com.relay.app.util.RelayPreferences

class MainActivity : FragmentActivity() {

    // Note: read once per composition of the lock gate; toggling "App lock" in Settings while
    // Relay is already in the foreground takes effect on the next cold start / resume-from-background,
    // not instantly — an accepted simplification rather than plumbing an observable prefs flow.
    private val isUnlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        isUnlocked.value = false
    }
}
