package com.relay.app.ui.screens.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.relay.app.messaging.PresenceSignals
import com.relay.app.messaging.PresenceBody
import kotlinx.coroutines.delay

@Composable
internal fun contactActivityLabel(key: String?): String {
    val statuses by PresenceSignals.observed.collectAsState()
    val now by produceState(System.currentTimeMillis(), statuses[key]) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    return PresenceBody.label(statuses[key],now)
}

/** Exists only inside the unlocked private navigation, and only resumes foreground leases. */
@Composable
fun PrivateActivityPresence() {
    val context=LocalContext.current.applicationContext
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        fun update(visible: Boolean) {
            com.relay.app.messaging.ActiveChat.foreground = visible
            PresenceSignals.visible(context, visible)
            if (visible) context.sendBroadcast(android.content.Intent(com.relay.app.messaging.Broadcasts.PRIVATE_FOREGROUND).setPackage(context.packageName))
        }
        val observer=LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_RESUME) update(true)
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) update(false)
        }
        owner.lifecycle.addObserver(observer)
        update(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); update(false) }
    }
}
