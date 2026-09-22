package com.relay.app.data.repository

import android.content.ContentValues
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
            Contacts.TABLE, null,
            "${Contacts.COL_DELETED_AT} IS NULL", null, null, null, "${Contacts.COL_NAME} ASC",
        )
        cursor.use { it.toContactList() }
    }

    /** Deletes the contact and, through the foreign key, every message in their chat. */
    suspend fun deleteContact(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(Contacts.TABLE, "${Contacts.COL_ID} = ?", arrayOf(id.toString()))
    }

    /**
     * Removes the contact from People and stops them being messageable, but leaves their chat history
     * in place. Clears their key so a message from the same person later starts a new contact rather
     * than silently reviving this one.
     */
    suspend fun softDeleteContact(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Contacts.COL_DELETED_AT, now)
            putNull(Contacts.COL_NOSTR_PUBKEY)
            putNull(Contacts.COL_PUBLIC_KEY)
            putNull(Contacts.COL_PENDING_PUBLIC_KEY)
            // phone is NOT NULL UNIQUE; a re-add from the same key later reuses the exact placeholder
            // this row used to hold, so it must be freed up here rather than left behind on a dead row.
            put(Contacts.COL_PHONE, "deleted:$id:$now")
        }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(id.toString()))
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

    fun setPublicKeySync(contactId: Long, publicKeyBase64: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_PUBLIC_KEY, publicKeyBase64) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    /**
     * Stores the contact's signing public key the first time we learn it (initial QR/SMS
     * pairing). Never overwrites an existing value — that key is the long-term trust anchor for
     * verifying later key rotations, so once set it can only change via the normal
     * pending-key-change review flow, exactly like [publicKey] itself.
     */
    fun setSigningPublicKeyIfAbsentSync(contactId: Long, signingKeyBase64: String) {
        val contact = getByIdSync(contactId) ?: return
        if (contact.signingPublicKey != null) return
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_SIGNING_PUBLIC_KEY, signingKeyBase64) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    fun setNameSync(contactId: Long, name: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_NAME, name) }
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

    /**
     * Deletes the contact only if no message was ever exchanged with it, so removing a half-finished
     * pairing can never take a real conversation with it. Returns whether it was deleted.
     */
    fun deleteIfUnusedSync(contactId: Long): Boolean {
        val db = dbHelper.writableDatabase
        val used = db.rawQuery(
            "SELECT 1 FROM ${com.relay.app.data.db.DatabaseContract.Messages.TABLE} WHERE ${com.relay.app.data.db.DatabaseContract.Messages.COL_CONTACT_ID} = ? LIMIT 1",
            arrayOf(contactId.toString()),
        ).use { it.moveToFirst() }
        if (used) return false
        return db.delete(Contacts.TABLE, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString())) > 0
    }

    fun findByNostrPubkeySync(pubkeyHex: String): Contact? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            Contacts.TABLE, null,
            "${Contacts.COL_NOSTR_PUBKEY} = ?", arrayOf(pubkeyHex.lowercase()),
            null, null, null, "1",
        )
        return cursor.use { if (it.moveToFirst()) it.toContact() else null }
    }

    /**
     * Returns the contact for [pubkeyHex], creating one (named [name]) if none exists. A brand-new
     * internet contact is marked as a Relay user immediately. If an existing contact is found its
     * name is only replaced when it is still the auto-generated placeholder.
     */
    fun findOrCreateByNostrSync(pubkeyHex: String, name: String, relayHints: List<String> = emptyList()): Contact {
        val key = pubkeyHex.lowercase()
        findByNostrPubkeySync(key)?.let { existing ->
            if (relayHints.isNotEmpty()) setRelayHintsSync(existing.id, relayHints)
            return findByNostrPubkeySync(key) ?: existing
        }
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Contacts.COL_NAME, name)
            put(Contacts.COL_PHONE, Contact.placeholderPhoneFor(key))
            put(Contacts.COL_NOSTR_PUBKEY, key)
            put(Contacts.COL_HAS_RELAY, 1)
            if (relayHints.isNotEmpty()) put(Contacts.COL_RELAY_HINTS, relayHints.joinToString("\n"))
        }
        db.insertWithOnConflict(Contacts.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        return findByNostrPubkeySync(key)
            ?: Contact(name = name, phone = Contact.placeholderPhoneFor(key), hasRelay = true, nostrPubkey = key)
    }

    /** Attaches a Nostr address to an existing (legacy phone) contact, e.g. after re-pairing by QR v3. */
    fun setNostrPubkeySync(contactId: Long, pubkeyHex: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(Contacts.COL_NOSTR_PUBKEY, pubkeyHex.lowercase())
            put(Contacts.COL_HAS_RELAY, 1)
        }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    /** Marks the contact as met in person (their QR code was scanned). */
    fun markQrVerifiedSync(contactId: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(Contacts.COL_QR_VERIFIED, 1) }
        db.update(Contacts.TABLE, values, "${Contacts.COL_ID} = ?", arrayOf(contactId.toString()))
    }

    fun setRelayHintsSync(contactId: Long, hints: List<String>) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (hints.isEmpty()) putNull(Contacts.COL_RELAY_HINTS)
            else put(Contacts.COL_RELAY_HINTS, hints.joinToString("\n"))
        }
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
        signingPublicKey = getColumnIndex(Contacts.COL_SIGNING_PUBLIC_KEY).let { idx ->
            if (idx >= 0 && !isNull(idx)) getString(idx) else null
        },
        nostrPubkey = getColumnIndex(Contacts.COL_NOSTR_PUBKEY).let { idx ->
            if (idx >= 0 && !isNull(idx)) getString(idx) else null
        },
        relayHints = getColumnIndex(Contacts.COL_RELAY_HINTS).let { idx ->
            if (idx >= 0 && !isNull(idx)) getString(idx).lines().filter { it.isNotBlank() } else emptyList()
        },
        qrVerified = getColumnIndex(Contacts.COL_QR_VERIFIED).let { idx ->
            idx >= 0 && !isNull(idx) && getInt(idx) == 1
        },
        deletedAt = getColumnIndex(Contacts.COL_DELETED_AT).let { idx ->
            if (idx >= 0 && !isNull(idx)) getLong(idx) else null
        },
    )
}
