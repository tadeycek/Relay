package com.relay.app.ui.screens.chat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Group
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.sms.SmsSender
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GroupChatViewModel(
    private val groupId: Long,
    app: Application,
) : AndroidViewModel(app) {

    private val db = RelayDbHelper(app)
    private val groupRepo = GroupRepository(db)
    private val groupMessageRepo = GroupMessageRepository(db)
    private val contactRepo = ContactRepository(db)

    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val groups = groupRepo.getAllGroups()
            _group.value = groups.find { it.id == groupId }
            _messages.value = groupMessageRepo.getMessages(groupId)
        }
    }

    fun registerUpdates(context: Context) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val incomingGroupId = intent.getLongExtra("group_id", -1L)
                if (incomingGroupId == groupId) loadData()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("com.relay.app.NEW_GROUP_MESSAGE"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregisterUpdates(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    fun sendText(body: String, context: Context) {
        val members = _group.value?.members ?: return
        viewModelScope.launch {
            val ts = System.currentTimeMillis()
            for (member in members) {
                SmsSender.sendSms(context, member.phone, body)
            }
            groupMessageRepo.insertMessage(GroupMessage(
                groupId = groupId,
                contactId = null,
                body = body,
                type = MessageType.TEXT,
                isSent = true,
                timestamp = ts,
            ))
            _messages.value = groupMessageRepo.getMessages(groupId)
        }
    }

    fun sendLocationRequest(context: Context) {
        val members = _group.value?.members ?: return
        viewModelScope.launch {
            val body = SmsMessageParser.LOCATION_REQUEST_MSG
            for (member in members) {
                SmsSender.sendSms(context, member.phone, body)
            }
            groupMessageRepo.insertMessage(GroupMessage(
                groupId = groupId,
                contactId = null,
                body = body,
                type = MessageType.LOCATION_REQUEST,
                isSent = true,
            ))
            _messages.value = groupMessageRepo.getMessages(groupId)
        }
    }

    suspend fun getMemberName(contactId: Long): String? =
        contactRepo.getById(contactId)?.name
}

class GroupChatViewModelFactory(
    private val groupId: Long,
    private val app: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return GroupChatViewModel(groupId, app) as T
    }
}
