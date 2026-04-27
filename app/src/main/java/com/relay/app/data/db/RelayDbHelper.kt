package com.relay.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RelayDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DatabaseContract.DB_NAME, null, DatabaseContract.DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(DatabaseContract.Contacts.CREATE)
        db.execSQL(DatabaseContract.Messages.CREATE)
        db.execSQL(DatabaseContract.Messages.INDEX_CONTACT)
        db.execSQL(DatabaseContract.Groups.CREATE)
        db.execSQL(DatabaseContract.GroupMembers.CREATE)
        db.execSQL(DatabaseContract.GroupMessages.CREATE)
        db.execSQL(DatabaseContract.GroupMessages.INDEX_GROUP)
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
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
