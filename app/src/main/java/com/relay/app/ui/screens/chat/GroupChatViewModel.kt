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
import com.relay.app.messaging.ActiveChat
import com.relay.app.messaging.Broadcasts
import com.relay.app.sms.RelaySecureSend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GroupChatViewModel(
    private val groupId: Long,
    app: Application,
) : AndroidViewModel(app) {

    private var historyLimit = com.relay.app.data.repository.HistoryWindow.PAGE_SIZE
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    fun loadOlder() { historyLimit = maxOf(historyLimit, _messages.value.size) + com.relay.app.data.repository.HistoryWindow.PAGE_SIZE; loadData(older = true) }
    private val db = com.relay.app.messaging.MessagingDb.get(app)
    private val groupRepo = GroupRepository(db)
    private val groupMessageRepo = GroupMessageRepository(db)
    private val contactRepo = ContactRepository(db)

    private val _friends = MutableStateFlow<List<com.relay.app.data.model.Contact>>(emptyList())
    val friends = _friends.asStateFlow()
    internal val acceptedDraft = MutableStateFlow<AcceptedDraft?>(null)
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()
    fun refresh() {
        loadData()
        com.relay.app.messaging.MessagingRuntime.launchIo { com.relay.app.groups.GroupMedia.retryPending(getApplication()) }
    }
    fun changeMember(context: Context, contact: Long, add: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { com.relay.app.groups.SharedGroups.changeMember(context, groupId, contact, add) } }
                .onFailure { _error.value = it.message ?: "Couldn't update members" }
            loadData()
        }
    }
    fun deleteGroup(context: Context, done: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { com.relay.app.groups.SharedGroups.delete(context, groupId) } }
                .onSuccess { done() }.onFailure { _error.value = it.message }
        }
    }
    fun sendMedia(context: Context, uri: android.net.Uri, mime: String, caption: String, done: () -> Unit) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val result = if (mime.startsWith("image/")) com.relay.app.media.GifPreparation.image(context, uri, mime)
                        else com.relay.app.mms.MediaCompressor.prepareVideo(context, uri, 12L*1024*1024, 60000L)
                    when (result) {
                        is com.relay.app.mms.MediaCompressor.Result.Success -> {
                            try {
                                com.relay.app.media.PendingAttachments.stage(context, groupId, true, result.file, result.mimeType,
                                    com.relay.app.media.MediaBody.normalizeCaption(caption))
                            } finally { result.file.delete() }
                        }
                        is com.relay.app.mms.MediaCompressor.Result.TooLarge -> error(result.message)
                        else -> error("Couldn't prepare attachment")
                    }
                }
                done()
            } catch (e: Exception) { _error.value = e.message ?: "Couldn't send attachment" }
            finally { _sending.value = false; loadData() }
        }
    }
    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    init { refresh() }

    private suspend fun readWindow(older: Boolean = false): List<com.relay.app.data.model.GroupMessage> {
        withContext(Dispatchers.IO) { com.relay.app.messaging.MessageRetention.sweep(getApplication()) }
        val first = if(older) null else _messages.value.firstOrNull()
        return groupMessageRepo.getMessages(groupId,historyLimit,first?.timestamp,first?.id ?: 0)
    }

    private fun loadData(older: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _friends.value = contactRepo.getAllContacts().filter(com.relay.app.groups.GroupProtocol::eligible)
            _group.value = groupRepo.getById(groupId)
            val firstLoad = _messages.value.isEmpty()
            val loaded = readWindow(older)
            if(firstLoad || older) _hasOlder.value = loaded.size == historyLimit
            _messages.value = loaded
            markReadIfVisible()
        }
    }

    /** While this group is on screen its messages count as seen (see ChatViewModel.markReadIfVisible). */
    private suspend fun markReadIfVisible() {
        if (ActiveChat.groupId != groupId) return
        val changed = withContext(Dispatchers.IO) { groupMessageRepo.markGroupReadSync(groupId) }
        if (changed > 0) Broadcasts.sendUnreadChanged(getApplication<Application>())
    }

    fun registerUpdates(context: Context) {
        ActiveChat.groupId = groupId
        com.relay.app.messaging.GroupMessageNotifier.cancel(context, groupId)
        viewModelScope.launch { markReadIfVisible() }
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
        if (ActiveChat.groupId == groupId) ActiveChat.groupId = -1L
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }
    fun sendText(body: String, context: Context, onQueued: () -> Unit = {}) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { com.relay.app.groups.SharedGroups.send(context, groupId, body) } }
                    .onSuccess { onQueued() }
                    .onFailure { _error.value = it.message ?: "Couldn't queue this message" }
                _messages.value = readWindow()
            } finally { _sending.value = false }
        }
    }
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
