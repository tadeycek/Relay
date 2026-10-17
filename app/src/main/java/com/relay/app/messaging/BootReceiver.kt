package com.relay.app.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.relay.app.util.RelayPreferences

/** Restores the background connection after a reboot (if the user has it enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MessagingRuntime.ensureStarted(context)
        if (RelayPreferences(context).backgroundConnection) ConnectionService.start(context)
    }
}
