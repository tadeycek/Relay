package com.relay.app.data.repository

import android.content.ContentValues
import com.relay.app.data.db.DatabaseContract.Contacts
import com.relay.app.data.db.DatabaseContract.GroupMembers
import com.relay.app.data.db.DatabaseContract.Groups
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
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

    private fun getMembersSync(groupId: Long): List<Contact> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT c.* FROM ${Contacts.TABLE} c INNER JOIN ${GroupMembers.TABLE} gm ON c.${Contacts.COL_ID} = gm.${GroupMembers.COL_CONTACT_ID} WHERE gm.${GroupMembers.COL_GROUP_ID} = ?",
            arrayOf(groupId.toString())
        )
        return cursor.use { c ->
            val list = mutableListOf<Contact>()
            while (c.moveToNext()) {
                val keyIdx = c.getColumnIndex(Contacts.COL_PUBLIC_KEY)
                list.add(Contact(
                    id = c.getLong(c.getColumnIndexOrThrow(Contacts.COL_ID)),
                    name = c.getString(c.getColumnIndexOrThrow(Contacts.COL_NAME)),
                    phone = c.getString(c.getColumnIndexOrThrow(Contacts.COL_PHONE)),
                    hasRelay = c.getInt(c.getColumnIndexOrThrow(Contacts.COL_HAS_RELAY)) == 1,
                    publicKey = if (keyIdx >= 0 && !c.isNull(keyIdx)) c.getString(keyIdx) else null,
                ))
            }
            list
        }
    }
}
