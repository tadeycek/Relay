package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.Messages
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.Cursor

class MessageRepository(private val dbHelper: RelayDbHelper) {

    suspend fun getMessages(contactId: Long): List<Message> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Messages.TABLE, null,
            "${Messages.COL_CONTACT_ID} = ?", arrayOf(contactId.toString()),
            null, null, "${Messages.COL_TIMESTAMP} ASC"
        )
        cursor.use { it.toMessageList() }
    }

    suspend fun insertMessage(msg: Message): Long = withContext(Dispatchers.IO) {
        insertMessageSync(msg)
    }

    fun insertMessageSync(msg: Message): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Messages.COL_CONTACT_ID, msg.contactId)
            put(Messages.COL_BODY, msg.body)
            put(Messages.COL_TYPE, msg.type.name)
            msg.lat?.let { put(Messages.COL_LAT, it) }
            msg.lng?.let { put(Messages.COL_LNG, it) }
            put(Messages.COL_IS_SENT, if (msg.isSent) 1 else 0)
            put(Messages.COL_TIMESTAMP, msg.timestamp)
            msg.mediaUri?.let { put(Messages.COL_MEDIA_URI, it) }
            msg.pinLabel?.let { put(Messages.COL_PIN_LABEL, it) }
            msg.expiryAt?.let { put(Messages.COL_EXPIRY_AT, it) }
            msg.msgId?.let { put(Messages.COL_MSG_ID, it) }
            put(Messages.COL_SENDER_VERIFIED, if (msg.senderVerified) 1 else 0)
            put(Messages.COL_DELIVERY_STATE, msg.deliveryState)
            put(Messages.COL_UNREAD, if (msg.unread) 1 else 0)
        }
        return db.insert(Messages.TABLE, null, values)
    }

    /** Clears the unread flag on everything received in this conversation (called when its chat is on screen). */
    fun markConversationReadSync(contactId: Long): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Messages.COL_UNREAD, 0) }
        return db.update(
            Messages.TABLE, values,
            "${Messages.COL_CONTACT_ID} = ? AND ${Messages.COL_UNREAD} = 1", arrayOf(contactId.toString()),
        )
    }

    /** Number of unread received messages across all one-to-one chats. */
    fun totalUnreadSync(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${Messages.TABLE} WHERE ${Messages.COL_UNREAD} = 1", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** True if a message with this payload id is already stored for [contactId] (idempotent receive). */
    fun hasMsgIdSync(contactId: Long, msgId: String): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Messages.TABLE, arrayOf(Messages.COL_ID),
            "${Messages.COL_CONTACT_ID} = ? AND ${Messages.COL_MSG_ID} = ?",
            arrayOf(contactId.toString(), msgId),
            null, null, null, "1",
        )
        return cursor.use { it.moveToFirst() }
    }

    /** Updates outgoing delivery progress for the message carrying payload id [msgId]. */
    fun setDeliveryStateSync(msgId: String, state: Int) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Messages.COL_DELIVERY_STATE, state) }
        db.update(
            Messages.TABLE, values,
            "${Messages.COL_MSG_ID} = ? AND ${Messages.COL_IS_SENT} = 1", arrayOf(msgId),
        )
    }

    fun hasMediaUri(uri: String): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Messages.TABLE, arrayOf(Messages.COL_ID),
            "${Messages.COL_MEDIA_URI} = ?", arrayOf(uri),
            null, null, null, "1"
        )
        return cursor.use { it.moveToFirst() }
    }

    fun deleteExpiredPinsSync() {
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.delete(
            Messages.TABLE,
            "${Messages.COL_TYPE} = ? AND ${Messages.COL_EXPIRY_AT} IS NOT NULL AND ${Messages.COL_EXPIRY_AT} <= ?",
            arrayOf(MessageType.LOCATION.name, now.toString())
        )
    }

    fun markReadUpToSync(contactId: Long, upToTimestamp: Long) {
        val tolerance = 2 * 60 * 1000L
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Messages.COL_READ_AT, System.currentTimeMillis()) }
        db.update(
            Messages.TABLE, values,
            "${Messages.COL_CONTACT_ID} = ? AND ${Messages.COL_IS_SENT} = 1 AND ${Messages.COL_TIMESTAMP} <= ? AND ${Messages.COL_READ_AT} IS NULL",
            arrayOf(contactId.toString(), (upToTimestamp + tolerance).toString())
        )
    }

    private fun Cursor.toMessageList(): List<Message> {
        val list = mutableListOf<Message>()
        while (moveToNext()) list.add(toMessage())
        return list
    }

    private fun Cursor.toMessage(): Message {
        val latIdx = getColumnIndexOrThrow(Messages.COL_LAT)
        val lngIdx = getColumnIndexOrThrow(Messages.COL_LNG)
        val mediaUriIdx = getColumnIndex(Messages.COL_MEDIA_URI)
        val pinLabelIdx = getColumnIndex(Messages.COL_PIN_LABEL)
        val expiryAtIdx = getColumnIndex(Messages.COL_EXPIRY_AT)
        val msgIdIdx = getColumnIndex(Messages.COL_MSG_ID)
        val readAtIdx = getColumnIndex(Messages.COL_READ_AT)
        val senderVerifiedIdx = getColumnIndex(Messages.COL_SENDER_VERIFIED)
        val deliveryStateIdx = getColumnIndex(Messages.COL_DELIVERY_STATE)
        val unreadIdx = getColumnIndex(Messages.COL_UNREAD)
        return Message(
            id = getLong(getColumnIndexOrThrow(Messages.COL_ID)),
            contactId = getLong(getColumnIndexOrThrow(Messages.COL_CONTACT_ID)),
            body = getString(getColumnIndexOrThrow(Messages.COL_BODY)),
            type = MessageType.fromDb(getString(getColumnIndexOrThrow(Messages.COL_TYPE))),
            lat = if (isNull(latIdx)) null else getDouble(latIdx),
            lng = if (isNull(lngIdx)) null else getDouble(lngIdx),
            isSent = getInt(getColumnIndexOrThrow(Messages.COL_IS_SENT)) == 1,
            timestamp = getLong(getColumnIndexOrThrow(Messages.COL_TIMESTAMP)),
            mediaUri = if (mediaUriIdx >= 0 && !isNull(mediaUriIdx)) getString(mediaUriIdx) else null,
            pinLabel = if (pinLabelIdx >= 0 && !isNull(pinLabelIdx)) getString(pinLabelIdx) else null,
            expiryAt = if (expiryAtIdx >= 0 && !isNull(expiryAtIdx)) getLong(expiryAtIdx) else null,
            msgId = if (msgIdIdx >= 0 && !isNull(msgIdIdx)) getString(msgIdIdx) else null,
            readAt = if (readAtIdx >= 0 && !isNull(readAtIdx)) getLong(readAtIdx) else null,
            senderVerified = if (senderVerifiedIdx >= 0 && !isNull(senderVerifiedIdx)) {
                getInt(senderVerifiedIdx) == 1
            } else true,
            deliveryState = if (deliveryStateIdx >= 0 && !isNull(deliveryStateIdx)) getInt(deliveryStateIdx) else 0,
            unread = unreadIdx >= 0 && !isNull(unreadIdx) && getInt(unreadIdx) == 1,
        )
    }
}
