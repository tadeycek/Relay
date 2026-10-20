package com.relay.app.data.db

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

/**
 * relay.db is opened through SQLCipher rather than the platform SQLite
 * implementation, so the file is encrypted at rest. The passphrase is
 * generated once and stored via [RelayDbPassphrase] (Keystore-backed
 * EncryptedSharedPreferences) — callers never see or handle it directly,
 * they just use `readableDatabase`/`writableDatabase` as before.
 */
class RelayDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DatabaseContract.DB_NAME, null, DatabaseContract.DB_VERSION) {

    private val appContext = context.applicationContext

    init {
        SQLiteDatabase.loadLibs(appContext)
    }

    // Declared as real Kotlin properties (not just same-named functions) so existing call sites
    // like `dbHelper.readableDatabase` keep working: Kotlin only auto-exposes getFoo()-as-.foo
    // for Java-declared members, not for same-named Kotlin functions we add ourselves.
    val readableDatabase: SQLiteDatabase
        get() = getReadableDatabase(RelayDbPassphrase.get(appContext))

    val writableDatabase: SQLiteDatabase
        get() = getWritableDatabase(RelayDbPassphrase.get(appContext))

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(DatabaseContract.Contacts.CREATE)
        db.execSQL(DatabaseContract.Messages.CREATE)
        db.execSQL(DatabaseContract.Messages.INDEX_CONTACT)
        db.execSQL(DatabaseContract.Groups.CREATE)
        db.execSQL(DatabaseContract.GroupMembers.CREATE)
        db.execSQL(DatabaseContract.GroupMessages.CREATE)
        db.execSQL(DatabaseContract.GroupMessages.INDEX_GROUP)
        db.execSQL(DatabaseContract.Contacts.INDEX_NOSTR_PUBKEY)
        db.execSQL(DatabaseContract.Messages.INDEX_MSG_ID)
        db.execSQL(DatabaseContract.Outbox.CREATE)
        db.execSQL(DatabaseContract.Outbox.INDEX_DUE)
        db.execSQL(DatabaseContract.SeenPayloads.CREATE)
        db.execSQL(DatabaseContract.Messages.INDEX_UNREAD)
        db.execSQL(DatabaseContract.GroupMessages.INDEX_UNREAD)
        createSharedGroups(db)
        createMessageActions(db)
        db.execSQL(com.relay.app.groups.GroupProfiles.CREATE)
        db.execSQL(com.relay.app.groups.GroupDelivery.CREATE)
        db.execSQL(com.relay.app.media.PendingAttachments.CREATE)
        createConversationExtras(db)
        db.execSQL(com.relay.app.messaging.PresenceSignals.CREATE)
        db.execSQL(com.relay.app.groups.SharedGroups.CREATE_PENDING)
        createMailbox(db)
        createDispatchAndReceipts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(DatabaseContract.Contacts.ADD_HAS_RELAY)
        }
        if (oldVersion < 3) {
            db.execSQL(DatabaseContract.Messages.ADD_MEDIA_URI)
        }
        if (oldVersion < 4) {
            db.execSQL(DatabaseContract.Messages.ADD_PIN_LABEL)
            db.execSQL(DatabaseContract.Messages.ADD_EXPIRY_AT)
            db.execSQL(DatabaseContract.Messages.ADD_MSG_ID)
            db.execSQL(DatabaseContract.Messages.ADD_READ_AT)
            db.execSQL(DatabaseContract.Groups.CREATE)
            db.execSQL(DatabaseContract.GroupMembers.CREATE)
            db.execSQL(DatabaseContract.GroupMessages.CREATE)
            db.execSQL(DatabaseContract.GroupMessages.INDEX_GROUP)
        }
        if (oldVersion < 5) {
            db.execSQL(DatabaseContract.Contacts.ADD_TRUST_LEVEL)
        }
        if (oldVersion < 6) {
            db.execSQL(DatabaseContract.Contacts.ADD_PUBLIC_KEY)
            db.execSQL(DatabaseContract.Contacts.ADD_SENT_PUBKEY)
        }
        if (oldVersion < 7) {
            db.execSQL(DatabaseContract.Contacts.ADD_PENDING_PUBLIC_KEY)
        }
        if (oldVersion < 8) {
            db.execSQL(DatabaseContract.Contacts.ADD_SIGNING_PUBLIC_KEY)
        }
        if (oldVersion < 9) {
            db.execSQL(DatabaseContract.Messages.ADD_SENDER_VERIFIED)
        }
        if (oldVersion < 10) {
            // Internet transport. Purely additive (no table rebuild): dropping/recreating contacts
            // would cascade-delete every message, so the NOT NULL UNIQUE phone column stays and
            // internet-only contacts get a synthetic placeholder there (see Contact.hasPhone).
            db.execSQL(DatabaseContract.Contacts.ADD_NOSTR_PUBKEY)
            db.execSQL(DatabaseContract.Contacts.ADD_RELAY_HINTS)
            db.execSQL(DatabaseContract.Contacts.INDEX_NOSTR_PUBKEY)
            db.execSQL(DatabaseContract.Messages.ADD_DELIVERY_STATE)
            db.execSQL(DatabaseContract.Messages.INDEX_MSG_ID)
            db.execSQL(DatabaseContract.Outbox.CREATE)
            db.execSQL(DatabaseContract.Outbox.INDEX_DUE)
        }
        if (oldVersion < 11) {
            db.execSQL(DatabaseContract.SeenPayloads.CREATE)
        }
        if (oldVersion < 12) {
            // "Met in person": set only by scanning the contact's QR code. Distinguishes a verified
            // contact from a stranger who merely knows our key (name and key are self-declared).
            db.execSQL(DatabaseContract.Contacts.ADD_QR_VERIFIED)
        }
        if (oldVersion < 13) {
            // Unread badges for the conversation list. Existing messages start as read (DEFAULT 0):
            // we cannot know what the user has seen, and a wall of "unread" after an update would be noise.
            db.execSQL(DatabaseContract.Messages.ADD_UNREAD)
            db.execSQL(DatabaseContract.GroupMessages.ADD_UNREAD)
            db.execSQL(DatabaseContract.Messages.INDEX_UNREAD)
            db.execSQL(DatabaseContract.GroupMessages.INDEX_UNREAD)
        }
        if (oldVersion < 14) {
            // "Delete, keep the chat": the contact row survives (soft-deleted) so its messages, which
            // reference it, are unaffected; only a real delete cascades and removes them.
            db.execSQL(DatabaseContract.Contacts.ADD_DELETED_AT)
        }
        if (oldVersion < 16) createSharedGroups(db)
        if (oldVersion < 17) createMessageActions(db)
        if (oldVersion < 18) {
            db.execSQL(com.relay.app.groups.GroupProfiles.CREATE)
            db.execSQL(com.relay.app.groups.GroupDelivery.CREATE)
            db.execSQL(com.relay.app.media.PendingAttachments.CREATE)
        }
        if (oldVersion < 19) createConversationExtras(db)
        if (oldVersion < 20) {
            // Direct group delivery: outbox entries may target a group member who isn't a contact.
            db.execSQL(DatabaseContract.Outbox.V20_RENAME)
            db.execSQL(DatabaseContract.Outbox.CREATE)
            db.execSQL(DatabaseContract.Outbox.V20_COPY)
            db.execSQL(DatabaseContract.Outbox.V20_DROP)
            db.execSQL(DatabaseContract.Outbox.INDEX_DUE)
            db.execSQL(com.relay.app.groups.SharedGroups.CREATE_PENDING)
        }
        if (oldVersion < 21) {
            db.execSQL(com.relay.app.messaging.PresenceSignals.CREATE)
            if (oldVersion >= 19) db.execSQL("ALTER TABLE reactions ADD COLUMN received_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE reactions SET received_at=?", arrayOf(System.currentTimeMillis()))
        }
        if (oldVersion < 22) createMailbox(db)
        if (oldVersion < 23) createDispatchAndReceipts(db)
        if (oldVersion < 15) {
            // Keeps the name a contact was first added under even after a local rename. For a contact
            // that already existed, the best available answer is whatever name it currently has.
            db.execSQL(DatabaseContract.Contacts.ADD_ORIGINAL_NAME)
            db.execSQL(
                "UPDATE ${DatabaseContract.Contacts.TABLE} SET ${DatabaseContract.Contacts.COL_ORIGINAL_NAME} = " +
                    "${DatabaseContract.Contacts.COL_NAME} WHERE ${DatabaseContract.Contacts.COL_ORIGINAL_NAME} IS NULL"
            )
        }
    }

    private fun createDispatchAndReceipts(db: SQLiteDatabase) {
        db.execSQL(com.relay.app.groups.GroupDispatch.CREATE)
        db.execSQL(com.relay.app.groups.GroupReadReceipts.CREATE)
        db.execSQL(com.relay.app.groups.GroupReadReceipts.CREATE_SENT)
        db.execSQL("ALTER TABLE group_delivery ADD COLUMN recipient TEXT")
        db.execSQL("ALTER TABLE group_delivery ADD COLUMN requires_source INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_expiry ON messages(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS group_messages_expiry ON group_messages(timestamp)")
    }

    private fun createMailbox(db: SQLiteDatabase) {
        db.execSQL(com.relay.app.transport.mailbox.MailboxStore.CREATE_INBOX)
        db.execSQL(com.relay.app.transport.mailbox.MailboxStore.CREATE_PACKETS)
    }

    private fun createConversationExtras(db: SQLiteDatabase) {
        db.execSQL(com.relay.app.groups.GroupAvatars.CREATE)
        db.execSQL(com.relay.app.messaging.ConversationEvents.CREATE)
        db.execSQL("CREATE INDEX IF NOT EXISTS history_contact ON messages(contact_id,timestamp DESC,_id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_group ON group_messages(group_id,timestamp DESC,_id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS group_delivery_event ON group_delivery(group_id,event)")
    }

    private fun createSharedGroups(db: SQLiteDatabase) {
        db.execSQL(com.relay.app.groups.SharedGroups.CREATE)
        db.execSQL(com.relay.app.groups.SharedGroups.CREATE_SEEN)
        db.execSQL("ALTER TABLE group_messages ADD COLUMN sender_key TEXT")
        db.execSQL("ALTER TABLE group_messages ADD COLUMN sender_name TEXT")
    }

    private fun createMessageActions(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE group_messages ADD COLUMN event_id TEXT")
        db.execSQL("ALTER TABLE group_messages ADD COLUMN media_ref TEXT")
        db.execSQL("CREATE INDEX group_event ON group_messages(group_id,event_id)")
        db.execSQL(com.relay.app.messaging.MessageDeletion.CREATE)
        db.execSQL(com.relay.app.messaging.MessageDeletion.CREATE_PURGES)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
