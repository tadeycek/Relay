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

    suspend fun getMessages(groupId: Long): List<GroupMessage> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            GroupMessages.TABLE, null,
            "${GroupMessages.COL_GROUP_ID} = ?", arrayOf(groupId.toString()),
            null, null, "${GroupMessages.COL_TIMESTAMP} ASC"
        )
        cursor.use { it.toGroupMessageList() }
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
            put(GroupMessages.COL_TYPE, msg.type.name)
            msg.lat?.let { put(GroupMessages.COL_LAT, it) }
            msg.lng?.let { put(GroupMessages.COL_LNG, it) }
            put(GroupMessages.COL_IS_SENT, if (msg.isSent) 1 else 0)
            put(GroupMessages.COL_TIMESTAMP, msg.timestamp)
            msg.mediaUri?.let { put(GroupMessages.COL_MEDIA_URI, it) }
            msg.pinLabel?.let { put(GroupMessages.COL_PIN_LABEL, it) }
            msg.expiryAt?.let { put(GroupMessages.COL_EXPIRY_AT, it) }
        }
        return db.insert(GroupMessages.TABLE, null, values)
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
            type = MessageType.fromDb(getString(getColumnIndexOrThrow(GroupMessages.COL_TYPE))),
            lat = if (isNull(latIdx)) null else getDouble(latIdx),
            lng = if (isNull(lngIdx)) null else getDouble(lngIdx),
            isSent = getInt(getColumnIndexOrThrow(GroupMessages.COL_IS_SENT)) == 1,
            timestamp = getLong(getColumnIndexOrThrow(GroupMessages.COL_TIMESTAMP)),
            mediaUri = if (mediaUriIdx >= 0 && !isNull(mediaUriIdx)) getString(mediaUriIdx) else null,
            pinLabel = if (pinLabelIdx >= 0 && !isNull(pinLabelIdx)) getString(pinLabelIdx) else null,
            expiryAt = if (expiryAtIdx >= 0 && !isNull(expiryAtIdx)) getLong(expiryAtIdx) else null,
        )
    }
}
