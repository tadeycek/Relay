package com.relay.app.ui.screens.contacts

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = RelayDbHelper(app)
    private val repo = ContactRepository(db)
    private val groupRepo = GroupRepository(db)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = loadAll()
    }

    init {
        loadAll()
        // Registered on the application, not the composable: pairing, key rotation and key-change
        // review state can all change while the People tab is not the one on screen (e.g. mid-pairing
        // on the QR screen). A receiver tied to the composable's own lifecycle would miss that broadcast
        // and never catch up, since nothing else forces a reload once the screen is visible again.
        ContextCompat.registerReceiver(
            app,
            updateReceiver,
            IntentFilter("com.relay.app.NEW_MESSAGE"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(updateReceiver) }
        super.onCleared()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _contacts.value = repo.getAllContacts()
            _groups.value = groupRepo.getAllGroups()
        }
    }

    /** Removes the contact and, through the database's foreign key, their whole message history. */
    fun deleteContact(id: Long) {
        viewModelScope.launch {
            repo.deleteContact(id)
            _contacts.value = repo.getAllContacts()
            notifyConversationsChanged(id)
        }
    }

    /** Removes the contact from People but keeps their chat in Messages, marked as a deleted contact. */
    fun softDeleteContact(id: Long) {
        viewModelScope.launch {
            repo.softDeleteContact(id)
            _contacts.value = repo.getAllContacts()
            notifyConversationsChanged(id)
        }
    }

    /**
     * The Messages tab's own ViewModel listens for this at the application level (see
     * MessagesViewModel), so without it a deleted contact's chat keeps showing there, stale, until
     * something unrelated happens to trigger a reload.
     */
    private fun notifyConversationsChanged(contactId: Long) {
        val app = getApplication<Application>()
        app.sendBroadcast(
            android.content.Intent(com.relay.app.messaging.Broadcasts.NEW_MESSAGE).apply {
                putExtra("contact_id", contactId)
                setPackage(app.packageName)
            }
        )
    }

    fun createGroup(name: String, memberIds: List<Long>) {
        if (name.isBlank() || memberIds.isEmpty()) return
        viewModelScope.launch {
            groupRepo.insertGroup(name.trim(), memberIds)
            _groups.value = groupRepo.getAllGroups()
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            groupRepo.deleteGroup(id)
            _groups.value = groupRepo.getAllGroups()
        }
    }

    /**
     * A name you set yourself always wins: a name the other side announces later only ever replaces
     * the auto-generated placeholder ("Contact 1a2b3c4d…"), never something you chose.
     */
    fun renameContact(contactId: Long, name: String) {
        val trimmed = name.trim().take(30)
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setNameSync(contactId, trimmed) }
            _contacts.value = repo.getAllContacts()
            notifyConversationsChanged(contactId)
        }
    }

    fun updateTrustLevel(contactId: Long, level: ContactTrustLevel) {
        viewModelScope.launch {
            repo.setTrustLevel(contactId, level)
            _contacts.value = repo.getAllContacts()
        }
    }

    fun acceptKeyChange(contactId: Long) {
        viewModelScope.launch {
            repo.acceptPendingPublicKeySync(contactId)
            _contacts.value = repo.getAllContacts()
        }
    }

    fun rejectKeyChange(contactId: Long) {
        viewModelScope.launch {
            repo.rejectPendingPublicKeySync(contactId)
            _contacts.value = repo.getAllContacts()
        }
    }

    fun removeMemberFromGroup(groupId: Long, contactId: Long) {
        viewModelScope.launch {
            groupRepo.removeMember(groupId, contactId)
            _groups.value = groupRepo.getAllGroups()
        }
    }

    fun addMemberToGroup(groupId: Long, contactId: Long) {
        viewModelScope.launch {
            groupRepo.addMember(groupId, contactId)
            _groups.value = groupRepo.getAllGroups()
        }
    }
}
