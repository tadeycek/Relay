package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.Outbox
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.transport.outbox.OutboxEntry
import com.relay.app.transport.outbox.OutboxState
import net.sqlcipher.Cursor

/** Persistent queue behind the transport: survives process death so a message is never lost mid-send. */
class OutboxRepository(private val dbHelper: RelayDbHelper) {

    fun enqueueSync(entry: OutboxEntry): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Outbox.COL_CONTACT_ID, entry.contactId)
            put(Outbox.COL_RECIPIENT, entry.recipientPubkeyHex)
            put(Outbox.COL_PAYLOAD_ID, entry.payloadId)
            put(Outbox.COL_PAYLOAD_JSON, entry.payloadJson)
            put(Outbox.COL_CREATED_AT, entry.createdAt)
            put(Outbox.COL_ATTEMPTS, entry.attempts)
            put(Outbox.COL_NEXT_ATTEMPT_AT, entry.nextAttemptAt)
            put(Outbox.COL_STATE, entry.state.dbValue)
        }
        return db.insert(Outbox.TABLE, null, values)
    }

    /** Queued entries whose retry time has arrived, oldest first. */
    fun dueSync(now: Long, limit: Int = 50): List<OutboxEntry> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Outbox.TABLE, null,
            "${Outbox.COL_STATE} = ? AND ${Outbox.COL_NEXT_ATTEMPT_AT} <= ?",
            arrayOf(OutboxState.QUEUED.dbValue.toString(), now.toString()),
            null, null, "${Outbox.COL_CREATED_AT} ASC", limit.toString(),
        )
        return cursor.use { c ->
            val list = mutableListOf<OutboxEntry>()
            while (c.moveToNext()) list.add(c.toEntry())
            list
        }
    }

    /** Earliest time any queued entry becomes due, or null when nothing is queued. */
    fun nextDueAtSync(): Long? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT MIN(${Outbox.COL_NEXT_ATTEMPT_AT}) FROM ${Outbox.TABLE} WHERE ${Outbox.COL_STATE} = ?",
            arrayOf(OutboxState.QUEUED.dbValue.toString()),
        )
        return cursor.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    fun queuedCountSync(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${Outbox.TABLE} WHERE ${Outbox.COL_STATE} = ?",
            arrayOf(OutboxState.QUEUED.dbValue.toString()),
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun markSentSync(id: Long) = setState(id, OutboxState.SENT)

    fun markFailedSync(id: Long) = setState(id, OutboxState.FAILED)

    fun recordAttemptSync(id: Long, attempts: Int, nextAttemptAt: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Outbox.COL_ATTEMPTS, attempts)
            put(Outbox.COL_NEXT_ATTEMPT_AT, nextAttemptAt)
        }
        db.update(Outbox.TABLE, values, "${Outbox.COL_ID} = ?", arrayOf(id.toString()))
    }

    /** Sent entries are only needed briefly (for status); drop them once older than [olderThan]. */
    fun purgeSentBeforeSync(olderThan: Long) {
        val db = dbHelper.writableDatabase
        db.delete(
            Outbox.TABLE,
            "${Outbox.COL_STATE} = ? AND ${Outbox.COL_CREATED_AT} < ?",
            arrayOf(OutboxState.SENT.dbValue.toString(), olderThan.toString()),
        )
    }

    private fun setState(id: Long, state: OutboxState) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Outbox.COL_STATE, state.dbValue) }
        db.update(Outbox.TABLE, values, "${Outbox.COL_ID} = ?", arrayOf(id.toString()))
    }

    private fun Cursor.toEntry() = OutboxEntry(
        id = getLong(getColumnIndexOrThrow(Outbox.COL_ID)),
        contactId = getLong(getColumnIndexOrThrow(Outbox.COL_CONTACT_ID)),
        recipientPubkeyHex = getString(getColumnIndexOrThrow(Outbox.COL_RECIPIENT)),
        payloadId = getString(getColumnIndexOrThrow(Outbox.COL_PAYLOAD_ID)),
        payloadJson = getString(getColumnIndexOrThrow(Outbox.COL_PAYLOAD_JSON)),
        createdAt = getLong(getColumnIndexOrThrow(Outbox.COL_CREATED_AT)),
        attempts = getInt(getColumnIndexOrThrow(Outbox.COL_ATTEMPTS)),
        nextAttemptAt = getLong(getColumnIndexOrThrow(Outbox.COL_NEXT_ATTEMPT_AT)),
        state = OutboxState.fromDb(getInt(getColumnIndexOrThrow(Outbox.COL_STATE))),
    )
}
