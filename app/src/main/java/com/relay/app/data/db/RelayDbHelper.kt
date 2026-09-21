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
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
