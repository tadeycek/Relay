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
    val now by produceState(System.currentTimeMillis()) { while(true) { delay(15_000); value=System.currentTimeMillis() } }
    return PresenceBody.label(statuses[key],now)
}

/** Exists only inside the unlocked private navigation, and only resumes foreground leases. */
@Composable
fun PrivateActivityPresence() {
    val context=LocalContext.current.applicationContext
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_RESUME) PresenceSignals.visible(context,true)
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) PresenceSignals.visible(context,false)
        }
        owner.lifecycle.addObserver(observer)
        PresenceSignals.visible(context,owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); PresenceSignals.visible(context,false) }
    }
}
