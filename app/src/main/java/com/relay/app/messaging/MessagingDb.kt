package com.relay.app.messaging

import android.content.Context
import com.relay.app.data.db.RelayDbHelper

/**
 * One shared [RelayDbHelper] for the messaging components (handler, outbox worker, notifier).
 * Opening a helper derives the SQLCipher passphrase from Keystore-backed storage and opens a
 * connection, so creating one per message would be slow, and several independent connections
 * writing the same file invites "database is locked" errors.
 */
object MessagingDb {
    @Volatile private var instance: RelayDbHelper? = null

    fun get(context: Context): RelayDbHelper =
        instance ?: synchronized(this) {
            instance ?: RelayDbHelper(context.applicationContext).also { instance = it }
        }
}

/** The chat the user currently has open, so a new message there does not also raise a notification. */
object ActiveChat {
    @Volatile var contactId: Long = -1L
    @Volatile var groupId: Long = -1L
}
