package com.relay.app.data.repository

/** Stable timestamp/id ordering makes equal-timestamp pages deterministic. */
object HistoryWindow {
    const val PAGE_SIZE = 200
    fun query(group: Boolean, retainedOnly: Boolean = false): String {
        val table = if(group) "group_messages" else "messages"
        val owner = if(group) "group_id" else "contact_id"
        val retained = if (retainedOnly) " AND timestamp>?" else ""
        return "SELECT * FROM (SELECT * FROM $table WHERE $owner=?$retained ORDER BY timestamp DESC,_id DESC LIMIT ?) ORDER BY timestamp ASC,_id ASC"
    }
}
