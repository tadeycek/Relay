package com.relay.app.data.repository

import android.content.ContentValues
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.relay.app.data.db.DatabaseContract.Contacts
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.Cursor

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

    /** Looks up a contact by phone, creating a minimal one (name = phone number) if none exists yet. */
    fun findOrCreateByPhoneSync(phone: String): Contact {
        findByPhoneSync(phone)?.let { return it }
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Contacts.COL_NAME, phone)
            put(Contacts.COL_PHONE, phone)
        }
        db.insertWithOnConflict(Contacts.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        return findByPhoneSync(phone) ?: Contact(name = phone, phone = phone)
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
        getByIdSync(id)
    }

    fun getByIdSync(id: Long): Contact? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, null,
            "${Contacts.COL_ID} = ?", arrayOf(id.toString()),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) it.toContact() else null }
    }

    private fun phoneMatches(stored: String, incoming: String): Boolean {
        // libphonenumber understands country codes/formatting variants; fall back to a
        // last-N-digit suffix comparison for numbers it can't parse (e.g. short local numbers).
        val matchType = try {
            PhoneNumberUtil.getInstance().isNumberMatch(stored, incoming)
        } catch (e: Exception) {
            null
        }
        when (matchType) {
            PhoneNumberUtil.MatchType.EXACT_MATCH,
            PhoneNumberUtil.MatchType.NSN_MATCH,
            PhoneNumberUtil.MatchType.SHORT_NSN_MATCH -> return true
            PhoneNumberUtil.MatchType.NO_MATCH -> return false
            else -> Unit
        }

        val s = stored.filter { it.isDigit() }
        val i = incoming.filter { it.isDigit() }
        val len = minOf(s.length, i.length, 9)
        if (len == 0) return false
        return s.takeLast(len) == i.takeLast(len)
    }

    fun setPublicKeySync(contactId: Long, publicKeyBase64: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_PUBLIC_KEY, publicKeyBase64) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    /** Holds an unverified candidate key without disturbing the currently-trusted one. */
    fun setPendingPublicKeySync(contactId: Long, pendingKeyBase64: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_PENDING_PUBLIC_KEY, pendingKeyBase64) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    /** User reviewed a key-change prompt and confirmed it: promote the pending key to trusted. */
    fun acceptPendingPublicKeySync(contactId: Long) {
        val contact = getByIdSync(contactId) ?: return
        val pending = contact.pendingPublicKey ?: return
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Contacts.COL_PUBLIC_KEY, pending)
            putNull(Contacts.COL_PENDING_PUBLIC_KEY)
        }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    /** User rejected the key-change prompt: discard the candidate, keep the previously-trusted key. */
    fun rejectPendingPublicKeySync(contactId: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { putNull(Contacts.COL_PENDING_PUBLIC_KEY) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    fun hasSentPubkeySync(contactId: Long): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, arrayOf(Contacts.COL_SENT_PUBKEY),
            "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) == 1 else false }
    }

    fun markSentPubkeySync(contactId: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_SENT_PUBKEY, 1) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
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
        publicKey = getColumnIndex(Contacts.COL_PUBLIC_KEY).let { idx ->
            if (idx >= 0 && !isNull(idx)) getString(idx) else null
        },
        pendingPublicKey = getColumnIndex(Contacts.COL_PENDING_PUBLIC_KEY).let { idx ->
            if (idx >= 0 && !isNull(idx)) getString(idx) else null
        },
    )
}
