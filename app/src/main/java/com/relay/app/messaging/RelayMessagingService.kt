package com.relay.app.messaging

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.relay.app.transport.mailbox.MailboxPush

/**
 * Receives the delivery server's wake-up pushes. They carry nothing but `type=wake`; the app connects
 * and catches up exactly as its periodic background check does, just right away.
 */
class RelayMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (com.relay.app.calculator.CalculatorOnly.enabled(applicationContext)) return
        if (message.data["type"] == "wake") {
            Log.i("MailboxPush", "wake-up push received")
            PollWorker.wakeNow(applicationContext)
        }
    }

    /** Called on a background thread whenever Firebase issues a new token for this install. */
    override fun onNewToken(token: String) {
        if (com.relay.app.calculator.CalculatorOnly.enabled(applicationContext)) return
        MailboxPush.refresh(applicationContext)
    }
}
