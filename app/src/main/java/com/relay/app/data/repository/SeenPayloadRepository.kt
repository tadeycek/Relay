package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.SeenPayloads
import com.relay.app.data.db.RelayDbHelper

/** Persistent "already processed" record so replayed relay events are harmless across restarts. */
class SeenPayloadRepository(private val dbHelper: RelayDbHelper) {

    /** Returns true the first time [payloadId] is recorded, false if it was already there. */
    fun markIfNewSync(payloadId: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(SeenPayloads.COL_PAYLOAD_ID, payloadId)
            put(SeenPayloads.COL_SEEN_AT, now)
        }
        // CONFLICT_IGNORE returns -1 when the primary key already exists.
        return db.insertWithOnConflict(SeenPayloads.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Forgets ids older than [RETENTION_MS]. Must exceed the transport's replay window (3 days + offline time). */
    fun purgeOldSync(now: Long = System.currentTimeMillis()) {
        val db = dbHelper.writableDatabase
        db.delete(SeenPayloads.TABLE, "${SeenPayloads.COL_SEEN_AT} < ?", arrayOf((now - RETENTION_MS).toString()))
    }

    companion object {
        const val RETENTION_MS = 60L * 24 * 60 * 60 * 1000
    }
}
