package com.relay.app.data.repository

import android.content.ContentValues
import android.database.Cursor
import com.relay.app.data.db.DatabaseContract.Contacts
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRepository(private val dbHelper: RelayDbHelper) {

    suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, null, null, null, null, null, "${Contacts.COL_NAME} ASC"
        )
        cursor.use { it.toContactList() }
    }

    suspend fun insertContact(name: String, phone: String): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Contacts.COL_NAME, name)
            put(Contacts.COL_PHONE, phone)
        }
        db.insertWithOnConflict(Contacts.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    }

    suspend fun deleteContact(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(Contacts.TABLE, "${Contacts.COL_ID} = ?", arrayOf(id.toString()))
    }

    suspend fun findByPhone(phone: String): Contact? = withContext(Dispatchers.IO) {
        findByPhoneSync(phone)
    }

    fun findByPhoneSync(phone: String): Contact? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, null, null, null, null, null, null
        )
        return cursor.use { c ->
            while (c.moveToNext()) {
                val stored = c.getString(c.getColumnIndexOrThrow(Contacts.COL_PHONE))
                if (phoneMatches(stored, phone)) return@use c.toContact()
            }
            null
        }
    }

    suspend fun getById(id: Long): Contact? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, null,
            "${Contacts.COL_ID} = ?", arrayOf(id.toString()),
            null, null, null
        )
        cursor.use { if (it.moveToFirst()) it.toContact() else null }
    }

    private fun phoneMatches(stored: String, incoming: String): Boolean {
        val s = stored.filter { it.isDigit() }
        val i = incoming.filter { it.isDigit() }
        val len = minOf(s.length, i.length, 9)
        if (len == 0) return false
        return s.takeLast(len) == i.takeLast(len)
    }

    private fun Cursor.toContactList(): List<Contact> {
        val list = mutableListOf<Contact>()
        while (moveToNext()) list.add(toContact())
        return list
    }

    fun markAsRelayUserSync(contactId: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_HAS_RELAY, 1) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    suspend fun setTrustLevel(contactId: Long, level: ContactTrustLevel) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_TRUST_LEVEL, level.dbValue) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    private fun Cursor.toContact() = Contact(
        id = getLong(getColumnIndexOrThrow(Contacts.COL_ID)),
        name = getString(getColumnIndexOrThrow(Contacts.COL_NAME)),
        phone = getString(getColumnIndexOrThrow(Contacts.COL_PHONE)),
        hasRelay = getInt(getColumnIndexOrThrow(Contacts.COL_HAS_RELAY)) == 1,
        trustLevel = ContactTrustLevel.fromDb(
            if (getColumnIndex(Contacts.COL_TRUST_LEVEL) >= 0) {
                getString(getColumnIndexOrThrow(Contacts.COL_TRUST_LEVEL))
            } else null
        ),
    )
}
