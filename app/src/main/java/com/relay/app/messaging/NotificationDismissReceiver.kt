package com.relay.app.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification taps clear only the preview; they never launch an activity or read/delete a chat. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        if (contactId > 0) MessageNotifier.cancel(context, contactId)
    }

    companion object {
        const val ACTION_DISMISS = "com.relay.app.DISMISS_MESSAGE_PREVIEW"
        const val EXTRA_CONTACT_ID = "dismiss_contact_id"
    }
}
