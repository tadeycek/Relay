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
    private var olderRequested = false
    private var localRevision = 0L
    private val localAcceptances = mutableMapOf<Long, Long>()
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
    fun sendMedia(context: Context, media: List<com.relay.app.media.PickedChatMedia>, caption: String,
        done: (com.relay.app.media.PickedChatMedia) -> Unit) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            var preparing: com.relay.app.media.PickedChatMedia? = null
            try {
                for ((index, item) in media.withIndex()) {
                    preparing = item
                    withContext(Dispatchers.IO) {
                        val result = if (item.mime.startsWith("image/")) com.relay.app.media.GifPreparation.image(context, item.uri, item.mime)
                            else com.relay.app.mms.MediaCompressor.prepareVideo(context, item.uri, com.relay.app.media.MediaBody.MAX_MEDIA_BYTES, Long.MAX_VALUE) { copied, total ->
                                com.relay.app.media.PendingAttachments.report(context, item.selectionId, "Preparing", copied, total)
                            }
                        when (result) {
                            is com.relay.app.mms.MediaCompressor.Result.Success -> {
                                try {
                                    com.relay.app.media.PendingAttachments.stage(context, groupId, true, result.file, result.mimeType,
                                        com.relay.app.media.MediaBody.normalizeCaption(if (index == 0) caption else ""), selectionId = item.selectionId)
                                } finally { result.file.delete() }
                            }
                            is com.relay.app.mms.MediaCompressor.Result.TooLarge -> error(result.message)
                            is com.relay.app.mms.MediaCompressor.Result.Error -> throw result.cause
                        }
                    }
                    done(item)
                    preparing = null
                    loadData()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                preparing?.let { item -> withContext(Dispatchers.IO) {
                    val previous = com.relay.app.media.PendingAttachments.progress.value[item.selectionId]
                    com.relay.app.media.PendingAttachments.report(context, item.selectionId, "Failed", previous?.transferred ?: 0,
                        previous?.total ?: 0, e.message ?: "Couldn't prepare attachment")
                } }
                _error.value = e.message ?: "Couldn't send attachment; remaining drafts kept"
            }
            finally { _sending.value = false; loadData() }
        }
    }
    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val refreshes = com.relay.app.messaging.RefreshQueue(viewModelScope, { exception ->
        _loadError.value = exception.message ?: "Couldn't load saved group messages"
    }) {
        val revision = localRevision
        val older = olderRequested
        olderRequested = false
        _loadError.value = null
        val firstLoad = !_loaded.value
        val loaded = readWindow(older)
        if (firstLoad || older) _hasOlder.value = loaded.size == historyLimit
        val additions = _messages.value.filter { (localAcceptances[it.id] ?: 0L) > revision }
        _messages.value = (loaded + additions.filter { added -> loaded.none { it.id == added.id } })
            .sortedWith(compareBy<GroupMessage> { it.timestamp }.thenBy { it.id })
        localAcceptances.entries.removeAll { it.value <= revision }
        _loaded.value = true
        _group.value = groupRepo.getById(groupId)
        markReadIfVisible()
        _friends.value = contactRepo.getAllContacts().filter(com.relay.app.groups.GroupProtocol::eligible)
    }

    private var receiver: BroadcastReceiver? = null

    init { refresh() }

    private suspend fun readWindow(older: Boolean = false): List<com.relay.app.data.model.GroupMessage> {
        val first = if(older || !_loaded.value) null else _messages.value.firstOrNull()
        return groupMessageRepo.getMessages(groupId,historyLimit,first?.timestamp,first?.id ?: 0)
    }

    private fun loadData(older: Boolean = false) {
        olderRequested = olderRequested || older
        refreshes.request()
    }

    fun reload() = loadData()
    fun resume() { ActiveChat.groupId = groupId; loadData() }

    /** While this group is on screen its messages count as seen (see ChatViewModel.markReadIfVisible). */
    private suspend fun markReadIfVisible() {
        if (ActiveChat.groupId != groupId || !ActiveChat.foreground || !_loaded.value) return
        val visible = _messages.value
        val changed = withContext(Dispatchers.IO) {
            if (!ActiveChat.foreground || ActiveChat.groupId != groupId) return@withContext 0
            val changed = groupMessageRepo.markGroupReadSync(groupId)
            com.relay.app.groups.GroupReadReceipts.send(getApplication(), groupId, visible)
            changed
        }
        if (changed > 0) Broadcasts.sendUnreadChanged(getApplication<Application>())
    }

    fun registerUpdates(context: Context) {
        ActiveChat.groupId = groupId
        com.relay.app.messaging.GroupMessageNotifier.cancel(context, groupId)
        viewModelScope.launch { markReadIfVisible() }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val incomingGroupId = intent.getLongExtra("group_id", -1L)
                if (incomingGroupId == groupId || intent.action == Broadcasts.PRIVATE_FOREGROUND) loadData()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("com.relay.app.NEW_GROUP_MESSAGE").apply { addAction(Broadcasts.PRIVATE_FOREGROUND) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadData()
    }

    fun unregisterUpdates(context: Context) {
        if (ActiveChat.groupId == groupId) ActiveChat.groupId = -1L
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    fun clearError() { _error.value = null }
    fun sendText(body: String, context: Context, onQueued: () -> Unit = {}) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) {
                    checkNotNull(com.relay.app.groups.SharedGroups.send(context, groupId, body)) { "Couldn't save this message; draft kept" }
                } }
                    .onSuccess { accepted ->
                        localRevision += 1
                        localAcceptances[accepted.id] = localRevision
                        _messages.value = (_messages.value.filterNot { it.id == accepted.id } + accepted)
                            .sortedWith(compareBy<GroupMessage> { it.timestamp }.thenBy { it.id })
                        onQueued()
                    }
                    .onFailure { _error.value = it.message ?: "Couldn't queue this message" }
                loadData()
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
