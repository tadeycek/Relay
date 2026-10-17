package com.relay.app.ui.screens.chat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.media.MediaSender
import com.relay.app.messaging.ActiveChat
import com.relay.app.messaging.Broadcasts
import com.relay.app.messaging.MessageNotifier
import com.relay.app.mms.MediaCompressor
import com.relay.app.sms.RelaySecureSend
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val contactId: Long,
    app: Application,
) : AndroidViewModel(app) {

    private var historyLimit = com.relay.app.data.repository.HistoryWindow.PAGE_SIZE
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    fun loadOlder() { historyLimit = maxOf(historyLimit, _messages.value.size) + com.relay.app.data.repository.HistoryWindow.PAGE_SIZE; loadData(older = true) }
    private val db = com.relay.app.messaging.MessagingDb.get(app)
    private val messageRepo = MessageRepository(db)
    private val contactRepo = ContactRepository(db)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var smsUpdateReceiver: BroadcastReceiver? = null

    init {
        loadData()
    }

    /**
     * The first message that was unread when the chat was opened, kept for as long as the chat is
     * open so the "new messages" divider stays put after the unread flags are cleared.
     */
    private val _unreadAnchorId = MutableStateFlow<Long?>(null)
    val unreadAnchorId: StateFlow<Long?> = _unreadAnchorId.asStateFlow()
    private var anchorCaptured = false

    private suspend fun readWindow(older: Boolean = false): List<com.relay.app.data.model.Message> {
        withContext(Dispatchers.IO) { com.relay.app.messaging.MessageRetention.sweep(getApplication()) }
        val first = if(older) null else _messages.value.firstOrNull()
        return messageRepo.getMessages(contactId,historyLimit,first?.timestamp,first?.id ?: 0)
    }

    private fun loadData(older: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _contact.value = contactRepo.getById(contactId)
            val loaded = readWindow(older)
            if(older || _messages.value.isEmpty()) _hasOlder.value = loaded.size == historyLimit
            _messages.value = loaded
            if (!anchorCaptured) {
                anchorCaptured = true
                _unreadAnchorId.value = loaded.firstOrNull { !it.isSent && it.unread }?.id
            }
            markReadIfVisible()
        }
    }

    /**
     * While this chat is on screen everything in it counts as seen: clear the unread flags, drop its
     * notification, and tell the list/badge. Broadcasts only when something actually changed, so this
     * cannot feed back into itself.
     */
    private suspend fun markReadIfVisible() {
        if (ActiveChat.contactId != contactId) return
        val changed = withContext(Dispatchers.IO) { messageRepo.markConversationReadSync(contactId) }
        if (changed > 0) {
            val app = getApplication<Application>()
            MessageNotifier.cancel(app, contactId)
            Broadcasts.sendUnreadChanged(app)
        }
    }

    fun registerSmsUpdates(context: Context) {
        ActiveChat.contactId = contactId // suppress notifications for the chat that is on screen
        viewModelScope.launch { markReadIfVisible() }
        // Media uses encrypted blob storage; opening a chat does not probe the peer's IP.
        smsUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val incomingContactId = intent.getLongExtra("contact_id", -1L)
                if (incomingContactId == contactId) loadData()
            }
        }
        ContextCompat.registerReceiver(
            context,
            smsUpdateReceiver,
            IntentFilter("com.relay.app.NEW_MESSAGE"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregisterSmsUpdates(context: Context) {
        if (ActiveChat.contactId == contactId) ActiveChat.contactId = -1L
        smsUpdateReceiver?.let { context.unregisterReceiver(it) }
        smsUpdateReceiver = null
    }

    internal val acceptedDraft = MutableStateFlow<AcceptedDraft?>(null)
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    fun sendText(body: String, context: Context, onQueued: () -> Unit = {}) {
        if (_sending.value) return
        val contact = _contact.value ?: return
        _sending.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val current = contactRepo.getByIdSync(contactId) ?: error("Contact no longer exists")
                    check(!current.isDeleted && current.trustLevel != com.relay.app.data.model.ContactTrustLevel.BLOCKED && current.pendingPublicKey == null) { "Review this contact before sending" }
                    val sql = db.writableDatabase
                    sql.beginTransaction()
                    try {
                        val handle = RelaySecureSend.sendWithId(context, contactRepo, current, body)
                        check(handle.ok) { "Couldn't queue message. Check this contact's pairing and try again." }
                        check(messageRepo.insertMessageSync(Message(contactId = contactId, body = body,
                            isSent = true, msgId = handle.msgId, deliveryState = DeliveryState.QUEUED)) != -1L)
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                }
                onQueued()
                _messages.value = readWindow()
                com.relay.app.messaging.MessagingRuntime.kick()
            } catch (e: Exception) { _toastMessage.emit(e.message ?: "Couldn't queue message; draft kept. Try again.") }
            finally { _sending.value = false }
        }
    }

    /** Internet sends start out queued (the outbox worker flips them to sent/failed); SMS has no such state. */
    private fun initialDeliveryState(contact: Contact, handle: RelaySecureSend.SendHandle): Int = when {
        !contact.canUseInternetTransport -> DeliveryState.NONE
        handle.ok -> DeliveryState.QUEUED
        else -> DeliveryState.FAILED
    }

    fun sendLocationRequest(context: Context) {
        val contact = _contact.value ?: return
        viewModelScope.launch {
            val body = SmsMessageParser.LOCATION_REQUEST_MSG
            val handle = withContext(Dispatchers.IO) { RelaySecureSend.sendWithId(context, contactRepo, contact, body) }
            if (!handle.ok) _toastMessage.emit("Location request failed to send")
            val msg = Message(
                contactId = contactId,
                body = body,
                type = MessageType.LOCATION_REQUEST,
                isSent = true,
                msgId = handle.msgId,
                deliveryState = initialDeliveryState(contact, handle),
            )
            messageRepo.insertMessage(msg)
            _messages.value = readWindow()
        }
    }

    fun sendReadReceipt(context: Context, lastReceivedTimestamp: Long) {
        val contact = _contact.value ?: return
        if (!RelayPreferences(context).readReceipts) return
        viewModelScope.launch {
            val body = SmsMessageParser.formatReadReceipt(lastReceivedTimestamp)
            RelaySecureSend.send(context, contactRepo, contact, body)
        }
    }

    /** Accept the encrypted local attachment before background upload/dispatch. */
    fun sendMedia(uri: Uri, mimeType: String, textBody: String, context: Context, onQueued: () -> Unit = {}) {
        if (_sending.value) return
        val contact = _contact.value ?: return
        _sending.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(contact.canUseInternetTransport) { "Scan this contact's QR code to message them" }
                    val prepared = if (mimeType.startsWith("image/"))
                        com.relay.app.media.GifPreparation.image(context, uri, mimeType)
                    else MediaCompressor.prepareVideo(context, uri, INTERNET_VIDEO_MAX_BYTES, INTERNET_VIDEO_MAX_MS)
                    when (prepared) {
                        is MediaCompressor.Result.Success -> {
                            try {
                                com.relay.app.media.PendingAttachments.stage(context, contactId, false, prepared.file, prepared.mimeType,
                                    com.relay.app.media.MediaBody.normalizeCaption(textBody))
                            } finally { prepared.file.delete() }
                        }
                        is MediaCompressor.Result.TooLarge -> error(prepared.message)
                        else -> error("Couldn't prepare attachment; try another file")
                    }
                }
                onQueued()
                _messages.value = readWindow()
                com.relay.app.messaging.MessagingRuntime.kick()
            } catch (e: Exception) { _toastMessage.emit(e.message ?: "Couldn't queue attachment; draft kept. Try again.") }
            finally { _sending.value = false }
        }
    }

}

// Internet sends are no longer bound by carrier MMS caps, but stay modest: the file is encrypted and
// uploaded in one piece, and there is no transcoding, so video is only size/length checked.
private const val INTERNET_IMAGE_MAX_BYTES = 3L * 1024 * 1024
private const val INTERNET_IMAGE_MAX_DIM = 2048
private const val INTERNET_VIDEO_MAX_BYTES = 12L * 1024 * 1024
private const val INTERNET_VIDEO_MAX_MS = 60_000L

class ChatViewModelFactory(
    private val contactId: Long,
    private val app: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(contactId, app) as T
    }
}
