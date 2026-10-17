package com.relay.app.ui.screens.chat

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.relay.app.messaging.ConversationEvents
import com.relay.app.messaging.MessagingRuntime

/** Android 14+ system callback. No media-store spying or capture-image access on older versions. */
@Composable
internal fun ConversationCaptureNotice(contact: Long? = null, group: Long? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    DisposableEffect(contact,group,lifecycle) {
        val activity = com.relay.app.nfc.NfcPairing.activityOf(context)
        val observer = if (Build.VERSION.SDK_INT >= 34 && activity != null) captureObserver(activity,lifecycle,contact,group) else null
        observer?.let { lifecycle.addObserver(it) }
        onDispose { observer?.let { lifecycle.removeObserver(it); it.onStateChanged(owner, Lifecycle.Event.ON_STOP) } }
    }
    Text(if(Build.VERSION.SDK_INT >= 34) "Screenshot notices are enabled. Some capture methods cannot be detected."
        else "Screenshot notices are unavailable on Android 13 and earlier.", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
}

@androidx.annotation.RequiresApi(34)
private fun captureObserver(activity: Activity,lifecycle: Lifecycle,contact:Long?,group:Long?): LifecycleEventObserver {
    var registered=false
    var last=0L
    val callback=Activity.ScreenCaptureCallback {
        val now=android.os.SystemClock.elapsedRealtime()
        if(registered && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && now-last>750) {
            last=now
            MessagingRuntime.launchIo {
                runCatching { ConversationEvents.screenshot(activity.applicationContext,contact,group) }
                    .onFailure { activity.runOnUiThread { android.widget.Toast.makeText(activity,"Screenshot notice couldn't be queued. Check this conversation's pairing.",android.widget.Toast.LENGTH_LONG).show() } }
            }
        }
    }
    return LifecycleEventObserver { _, event ->
        if(event==Lifecycle.Event.ON_START && !registered) {
            activity.registerScreenCaptureCallback(activity.mainExecutor,callback); registered=true
        } else if(event==Lifecycle.Event.ON_STOP && registered) {
            activity.unregisterScreenCaptureCallback(callback); registered=false
        }
    }
}
