package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.GroupMessages
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.Cursor

class GroupMessageRepository(private val dbHelper: RelayDbHelper) {

    suspend fun getMessages(groupId: Long, limit: Int? = null, fromTimestamp: Long? = null, fromId: Long = 0): List<GroupMessage> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cutoff = com.relay.app.messaging.RetentionPolicy.cutoff(System.currentTimeMillis()).toString()
        val cursor = if(fromTimestamp == null) db.rawQuery(HistoryWindow.query(true, retainedOnly = true), arrayOf(groupId.toString(), cutoff, (limit ?: -1).toString()))
        else db.rawQuery("SELECT * FROM group_messages WHERE group_id=? AND timestamp>? AND (timestamp>? OR (timestamp=? AND _id>=?)) ORDER BY timestamp ASC,_id ASC",
            arrayOf(groupId.toString(),cutoff,fromTimestamp.toString(),fromTimestamp.toString(),fromId.toString()))
        val loaded = cursor.use { it.toGroupMessageList() }
        val names = loaded.mapNotNull { it.senderKey }.distinct().associateWith { key ->
            com.relay.app.groups.GroupProfiles.display(dbHelper, groupId, key, loaded.first { it.senderKey == key }.senderName.orEmpty())
        }
        val states = com.relay.app.groups.GroupDelivery.states(dbHelper, groupId, loaded.mapNotNull { it.eventId })
        val reads = com.relay.app.groups.GroupReadReceipts.states(dbHelper, groupId, loaded.filter { it.isSent }.mapNotNull { it.eventId })
        loaded.map { message -> message.copy(senderName = names[message.senderKey] ?: message.senderName,
            readAt = reads[message.eventId]?.readAt, readCount = reads[message.eventId]?.count ?: 0,
            readerCount = reads[message.eventId]?.expected ?: 0,
            deliveryState = if(message.isSent) states[message.eventId] ?: com.relay.app.data.model.DeliveryState.NONE else com.relay.app.data.model.DeliveryState.NONE) }

    }

    fun getByEventSync(groupId: Long, event: String, author: String): GroupMessage? =
        dbHelper.readableDatabase.rawQuery("SELECT * FROM group_messages WHERE group_id=? AND event_id=? AND sender_key=?",
            arrayOf(groupId.toString(), event, author)).use { cursor ->
            if (cursor.moveToFirst()) cursor.toGroupMessage().copy(deliveryState = com.relay.app.data.model.DeliveryState.QUEUED) else null
        }

    suspend fun insertMessage(msg: GroupMessage): Long = withContext(Dispatchers.IO) {
        insertMessageSync(msg)
    }

    fun insertMessageSync(msg: GroupMessage): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(GroupMessages.COL_GROUP_ID, msg.groupId)
            msg.contactId?.let { put(GroupMessages.COL_CONTACT_ID, it) }
            put(GroupMessages.COL_BODY, msg.body)
            put("event_id", msg.eventId)
            put("sender_key", msg.senderKey)
            put("sender_name", msg.senderName)
            put(GroupMessages.COL_TYPE, msg.type.name)
            msg.lat?.let { put(GroupMessages.COL_LAT, it) }
            msg.lng?.let { put(GroupMessages.COL_LNG, it) }
            put(GroupMessages.COL_IS_SENT, if (msg.isSent) 1 else 0)
            put(GroupMessages.COL_TIMESTAMP, msg.timestamp)
            msg.mediaUri?.let { put(GroupMessages.COL_MEDIA_URI, it) }
            msg.pinLabel?.let { put(GroupMessages.COL_PIN_LABEL, it) }
            msg.expiryAt?.let { put(GroupMessages.COL_EXPIRY_AT, it) }
            put(GroupMessages.COL_UNREAD, if (msg.unread) 1 else 0)
        }
        return db.insert(GroupMessages.TABLE, null, values)
    }

    /** Clears the unread flag on everything received in this group (called when its chat is on screen). */
    fun markGroupReadSync(groupId: Long): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(GroupMessages.COL_UNREAD, 0) }
        return db.update(
            GroupMessages.TABLE, values,
            "${GroupMessages.COL_GROUP_ID} = ? AND ${GroupMessages.COL_UNREAD} = 1", arrayOf(groupId.toString()),
        )
    }

    /** Number of unread received messages across all groups. */
    fun totalUnreadSync(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${GroupMessages.TABLE} WHERE ${GroupMessages.COL_UNREAD} = 1 AND timestamp>?",
            arrayOf(com.relay.app.messaging.RetentionPolicy.cutoff(System.currentTimeMillis()).toString()))
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun deleteExpiredPinsSync() {
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.delete(
            GroupMessages.TABLE,
            "${GroupMessages.COL_TYPE} = ? AND ${GroupMessages.COL_EXPIRY_AT} IS NOT NULL AND ${GroupMessages.COL_EXPIRY_AT} <= ?",
            arrayOf(MessageType.LOCATION.name, now.toString())
        )
    }

    private fun Cursor.toGroupMessageList(): List<GroupMessage> {
        val list = mutableListOf<GroupMessage>()
        while (moveToNext()) list.add(toGroupMessage())
        return list
    }

    private fun Cursor.toGroupMessage(): GroupMessage {
        val latIdx = getColumnIndexOrThrow(GroupMessages.COL_LAT)
        val lngIdx = getColumnIndexOrThrow(GroupMessages.COL_LNG)
        val contactIdIdx = getColumnIndexOrThrow(GroupMessages.COL_CONTACT_ID)
        val mediaUriIdx = getColumnIndex(GroupMessages.COL_MEDIA_URI)
        val pinLabelIdx = getColumnIndex(GroupMessages.COL_PIN_LABEL)
        val expiryAtIdx = getColumnIndex(GroupMessages.COL_EXPIRY_AT)
        return GroupMessage(
            id = getLong(getColumnIndexOrThrow(GroupMessages.COL_ID)),
            groupId = getLong(getColumnIndexOrThrow(GroupMessages.COL_GROUP_ID)),
            contactId = if (isNull(contactIdIdx)) null else getLong(contactIdIdx),
            body = getString(getColumnIndexOrThrow(GroupMessages.COL_BODY)),
            eventId = getString(getColumnIndexOrThrow("event_id")),
            senderKey = getString(getColumnIndexOrThrow("sender_key")),
            senderName = getString(getColumnIndexOrThrow("sender_name")),
            type = MessageType.fromDb(getString(getColumnIndexOrThrow(GroupMessages.COL_TYPE))),
            lat = if (isNull(latIdx)) null else getDouble(latIdx),
            lng = if (isNull(lngIdx)) null else getDouble(lngIdx),
            isSent = getInt(getColumnIndexOrThrow(GroupMessages.COL_IS_SENT)) == 1,
            timestamp = getLong(getColumnIndexOrThrow(GroupMessages.COL_TIMESTAMP)),
            mediaUri = if (mediaUriIdx >= 0 && !isNull(mediaUriIdx)) getString(mediaUriIdx) else null,
            pinLabel = if (pinLabelIdx >= 0 && !isNull(pinLabelIdx)) getString(pinLabelIdx) else null,
            expiryAt = if (expiryAtIdx >= 0 && !isNull(expiryAtIdx)) getLong(expiryAtIdx) else null,
            unread = getInt(getColumnIndexOrThrow(GroupMessages.COL_UNREAD)) != 0,
        )
    }
}
