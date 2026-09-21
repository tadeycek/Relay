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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = RelayDbHelper(app)
    private val repo = ContactRepository(db)
    private val groupRepo = GroupRepository(db)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private var updateReceiver: BroadcastReceiver? = null

    init {
        loadAll()
    }

    /**
     * Without this, pairing, key rotation, and key-change review state (contact.publicKey,
     * signingPublicKey, pendingPublicKey) only ever refreshed after an explicit user action in
     * this screen — SmsReceiver mutates that state from a background broadcast, so without
     * listening for it the lock icon and the key-change dialog silently never appeared until the
     * app was killed and reopened, even though the security notification fired correctly.
     */
    fun registerUpdates(context: Context) {
        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = loadAll()
        }
        ContextCompat.registerReceiver(
            context,
            updateReceiver,
            IntentFilter("com.relay.app.NEW_MESSAGE"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregisterUpdates(context: Context) {
        updateReceiver?.let { context.unregisterReceiver(it) }
        updateReceiver = null
    }

    private fun loadAll() {
        viewModelScope.launch {
            _contacts.value = repo.getAllContacts()
            _groups.value = groupRepo.getAllGroups()
        }
    }

    fun deleteContact(id: Long) {
        viewModelScope.launch {
            repo.deleteContact(id)
            _contacts.value = repo.getAllContacts()
        }
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
