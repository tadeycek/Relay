package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.Contacts
import com.relay.app.data.db.DatabaseContract.GroupMembers
import com.relay.app.data.db.DatabaseContract.Groups
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GroupRepository(private val dbHelper: RelayDbHelper) {

    suspend fun getAllGroups(): List<Group> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(Groups.TABLE, null, null, null, null, null, "${Groups.COL_NAME} ASC")
        val groups = cursor.use { c ->
            val list = mutableListOf<Group>()
            while (c.moveToNext()) {
                list.add(Group(
                    id = c.getLong(c.getColumnIndexOrThrow(Groups.COL_ID)),
                    name = c.getString(c.getColumnIndexOrThrow(Groups.COL_NAME)),
                ))
            }
            list
        }
        groups.map { g -> g.copy(members = getMembersSync(g.id)) }
    }

    suspend fun insertGroup(name: String, memberContactIds: List<Long>): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val groupId = db.insert(Groups.TABLE, null, ContentValues().apply { put(Groups.COL_NAME, name) })
        if (groupId == -1L) return@withContext -1L
        for (contactId in memberContactIds) {
            db.insert(GroupMembers.TABLE, null, ContentValues().apply {
                put(GroupMembers.COL_GROUP_ID, groupId)
                put(GroupMembers.COL_CONTACT_ID, contactId)
            })
        }
        groupId
    }

    suspend fun deleteGroup(groupId: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(Groups.TABLE, "${Groups.COL_ID} = ?", arrayOf(groupId.toString()))
    }

    suspend fun getGroupsForContact(contactId: Long): List<Group> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT g.* FROM ${Groups.TABLE} g INNER JOIN ${GroupMembers.TABLE} gm ON g.${Groups.COL_ID} = gm.${GroupMembers.COL_GROUP_ID} WHERE gm.${GroupMembers.COL_CONTACT_ID} = ?",
            arrayOf(contactId.toString())
        )
        cursor.use { c ->
            val list = mutableListOf<Group>()
            while (c.moveToNext()) {
                list.add(Group(
                    id = c.getLong(c.getColumnIndexOrThrow(Groups.COL_ID)),
                    name = c.getString(c.getColumnIndexOrThrow(Groups.COL_NAME)),
                ))
            }
            list
        }
    }

    suspend fun addMember(groupId: Long, contactId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.insertWithOnConflict(
            GroupMembers.TABLE, null,
            ContentValues().apply {
                put(GroupMembers.COL_GROUP_ID, groupId)
                put(GroupMembers.COL_CONTACT_ID, contactId)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    suspend fun removeMember(groupId: Long, contactId: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            GroupMembers.TABLE,
            "${GroupMembers.COL_GROUP_ID} = ? AND ${GroupMembers.COL_CONTACT_ID} = ?",
            arrayOf(groupId.toString(), contactId.toString()),
        )
    }

    /**
     * Members as full [Contact]s. These used to be built by hand from a subset of columns, which silently
     * dropped the Nostr key and relay hints: a group message to such a member could not be queued (no
     * address), so group sends failed without any error. Loading through [ContactRepository] keeps the
     * mapping in one place so new contact fields cannot be forgotten here again.
     */
    private fun getMembersSync(groupId: Long): List<Contact> {
        val db = dbHelper.readableDatabase
        val ids = db.rawQuery(
            "SELECT ${GroupMembers.COL_CONTACT_ID} FROM ${GroupMembers.TABLE} WHERE ${GroupMembers.COL_GROUP_ID} = ?",
            arrayOf(groupId.toString())
        ).use { c ->
            val list = mutableListOf<Long>()
            while (c.moveToNext()) list.add(c.getLong(0))
            list
        }
        val contacts = ContactRepository(dbHelper)
        return ids.mapNotNull { contacts.getByIdSync(it) }
    }
}
