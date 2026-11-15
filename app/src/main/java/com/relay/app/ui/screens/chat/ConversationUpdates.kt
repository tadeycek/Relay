package com.relay.app.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Bind before refreshing; a foreground broadcast sent while navigation was absent is not replayed. */
@Composable
internal fun ConversationUpdates(key: Any, bind: () -> Unit, unbind: () -> Unit, refresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, key) {
        bind()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); unbind() }
    }
}
