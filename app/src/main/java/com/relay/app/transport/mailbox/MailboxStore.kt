package com.relay.app.transport.mailbox

import android.content.ContentValues
import com.relay.app.data.db.RelayDbHelper
import net.sqlcipher.database.SQLiteDatabase

/**
 * The mailbox transport's two durable tables in the encrypted app database.
 *
 *  - `mailbox_inbox`: every packet the server hands over is stored here *before* the server is told
 *    it arrived, and stays until the message handler has dealt with it. A crash in between loses
 *    nothing, and a packet that keeps failing is dropped after [MAX_ATTEMPTS] app starts instead of
 *    blocking the queue (receipts are cumulative, so the server can't hold it back on its own).
 *  - `mailbox_packets`: the gift wrap built for each (payload id, recipient) the first time it is
 *    sent, reused byte for byte on every retry so the server recognises the retry.
 */
class MailboxStore(private val db: RelayDbHelper) {

    /** Stores the messages not already held; returns the seqs that are new. */
    fun storeAll(messages: List<MailboxMessage>, now: Long = System.currentTimeMillis()): List<Long> {
        val sql = db.writableDatabase
        val fresh = mutableListOf<Long>()
        sql.beginTransaction()
        try {
            for (m in messages) {
                val values = ContentValues().apply {
                    put("seq", m.seq)
                    put("envelope", m.envelope)
                    put("received_at", now)
                }
                if (sql.insertWithOnConflict(INBOX, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh += m.seq
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        return fresh
    }

    fun envelope(seq: Long): String? =
        db.readableDatabase.rawQuery("SELECT envelope FROM $INBOX WHERE seq=?", arrayOf(seq.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Packets left over from an earlier run, oldest first, counting this as another attempt. Those
     * that have already had [MAX_ATTEMPTS] are deleted instead: something about them always fails.
     */
    fun replayable(): List<Long> {
        val sql = db.writableDatabase
        sql.execSQL("DELETE FROM $INBOX WHERE attempts >= ?", arrayOf<Any>(MAX_ATTEMPTS))
        sql.execSQL("UPDATE $INBOX SET attempts = attempts + 1")
        return sql.rawQuery("SELECT seq FROM $INBOX ORDER BY seq", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    fun delete(seq: Long) {
        db.writableDatabase.delete(INBOX, "seq=?", arrayOf(seq.toString()))
    }

    fun packet(payloadId: String, recipient: String): String? =
        db.readableDatabase.rawQuery(
            "SELECT envelope FROM $PACKETS WHERE payload_id=? AND recipient=?", arrayOf(payloadId, recipient),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Keeps the first packet saved for this pair and returns whichever is stored. */
    fun savePacket(payloadId: String, recipient: String, envelope: String, now: Long = System.currentTimeMillis()): String {
        val values = ContentValues().apply {
            put("payload_id", payloadId)
            put("recipient", recipient)
            put("envelope", envelope)
            put("created_at", now)
        }
        db.writableDatabase.insertWithOnConflict(PACKETS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return packet(payloadId, recipient) ?: envelope
    }

    fun deletePacket(payloadId: String, recipient: String) {
        db.writableDatabase.delete(PACKETS, "payload_id=? AND recipient=?", arrayOf(payloadId, recipient))
    }

    /** The outbox gives up after seven days, so older packets can never be sent again. */
    fun purgePackets(now: Long = System.currentTimeMillis()) {
        db.writableDatabase.delete(PACKETS, "created_at < ?", arrayOf((now - PACKET_RETENTION_MS).toString()))
    }

    companion object {
        const val INBOX = "mailbox_inbox"
        const val PACKETS = "mailbox_packets"
        const val MAX_ATTEMPTS = 5
        const val PACKET_RETENTION_MS = 8L * 24 * 60 * 60 * 1000

        const val CREATE_INBOX = """CREATE TABLE IF NOT EXISTS $INBOX (
            seq INTEGER PRIMARY KEY,
            envelope TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0
        )"""

        const val CREATE_PACKETS = """CREATE TABLE IF NOT EXISTS $PACKETS (
            payload_id TEXT NOT NULL,
            recipient TEXT NOT NULL,
            envelope TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (payload_id, recipient)
        )"""
    }
}
