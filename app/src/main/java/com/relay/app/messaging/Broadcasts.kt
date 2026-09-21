package com.relay.app.messaging

import android.content.Context
import android.content.Intent

/** Internal (package-local) broadcast actions used to refresh UI state. */
object Broadcasts {
    const val NEW_MESSAGE = "com.relay.app.NEW_MESSAGE"
    const val NEW_GROUP_MESSAGE = "com.relay.app.NEW_GROUP_MESSAGE"

    /**
     * Read state changed (a chat was opened). Deliberately separate from [NEW_MESSAGE]: an open chat reloads
     * on NEW_MESSAGE and marks itself read, so reusing that action would loop forever.
     */
    const val UNREAD_CHANGED = "com.relay.app.UNREAD_CHANGED"

    val ALL_CONVERSATION_EVENTS = arrayOf(NEW_MESSAGE, NEW_GROUP_MESSAGE, UNREAD_CHANGED)

    fun sendUnreadChanged(context: Context) {
        context.sendBroadcast(Intent(UNREAD_CHANGED).setPackage(context.packageName))
    }
}
