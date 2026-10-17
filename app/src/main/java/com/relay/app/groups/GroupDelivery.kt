package com.relay.app.groups

import android.content.Context
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.DeliveryState
import com.relay.app.messaging.MessagingDb

/** Tracks each durable fanout leg. SENT means relay acceptance, never recipient delivery. */
object GroupDelivery {
    const val CREATE = """CREATE TABLE group_delivery (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, payload_id TEXT PRIMARY KEY, state INTEGER NOT NULL DEFAULT 1)"""
    fun state(db: RelayDbHelper, group: Long, event: String?): Int {
        if (event == null) return DeliveryState.NONE
        return db.readableDatabase.rawQuery("SELECT state FROM group_delivery WHERE group_id=? AND event=?",
            arrayOf(group.toString(), event)).use { c ->
            val states = buildList { while (c.moveToNext()) add(c.getInt(0)) }
            when {
                states.isEmpty() -> DeliveryState.NONE
                DeliveryState.FAILED in states -> DeliveryState.FAILED
                DeliveryState.QUEUED in states -> DeliveryState.QUEUED
                else -> DeliveryState.SENT
            }
        }
    }
    fun states(db: RelayDbHelper, group: Long, events: List<String>): Map<String,Int> = buildMap {
        for (chunk in events.distinct().chunked(500)) {
            if (chunk.isEmpty()) continue
            val marks = chunk.joinToString(",") { "?" }
            db.readableDatabase.rawQuery("SELECT event,CASE WHEN MAX(state=3)=1 THEN 3 WHEN MAX(state=1)=1 THEN 1 ELSE 2 END FROM group_delivery WHERE group_id=? AND event IN ($marks) GROUP BY event",
                (listOf(group.toString()) + chunk).toTypedArray()).use { c -> while(c.moveToNext()) put(c.getString(0),c.getInt(1)) }
        }
    }
    fun update(context: Context, payload: String, state: Int) {
        val db = MessagingDb.get(context)
        val ids = db.readableDatabase.rawQuery("SELECT group_id FROM group_delivery WHERE payload_id=?", arrayOf(payload))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        db.writableDatabase.execSQL("UPDATE group_delivery SET state=? WHERE payload_id=?", arrayOf(state, payload))
        ids.forEach { SharedGroups.notify(context, it) }
    }
}
