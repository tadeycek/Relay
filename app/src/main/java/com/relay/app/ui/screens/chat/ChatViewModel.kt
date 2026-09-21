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

    private val db = RelayDbHelper(app)
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

    private fun loadData() {
        viewModelScope.launch {
            _contact.value = contactRepo.getById(contactId)
            _messages.value = messageRepo.getMessages(contactId)
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

    fun sendText(body: String, context: Context) {
        val contact = _contact.value ?: return
        viewModelScope.launch {
            val ts = System.currentTimeMillis()
            val handle = withContext(Dispatchers.IO) { RelaySecureSend.sendWithId(context, contactRepo, contact, body) }
            if (!handle.ok) _toastMessage.emit("Message failed to send")
            val msg = Message(
                contactId = contactId,
                body = body,
                type = MessageType.TEXT,
                isSent = true,
                timestamp = ts,
                msgId = handle.msgId ?: ts.toString(),
                deliveryState = initialDeliveryState(contact, handle),
            )
            messageRepo.insertMessage(msg)
            _messages.value = messageRepo.getMessages(contactId)
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
            _messages.value = messageRepo.getMessages(contactId)
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

    /** Compress, encrypt, upload to a media server, then queue the reference like any other message. */
    private fun sendMediaOverInternet(uri: Uri, mimeType: String, caption: String, contact: Contact, context: Context) {
        viewModelScope.launch {
            _toastMessage.emit("Sending...")
            val outcome = withContext(Dispatchers.IO) {
                val prepared = if (mimeType.startsWith("image")) {
                    MediaCompressor.compressImage(context, uri, INTERNET_IMAGE_MAX_BYTES, INTERNET_IMAGE_MAX_DIM)
                } else {
                    MediaCompressor.prepareVideo(context, uri, INTERNET_VIDEO_MAX_BYTES, INTERNET_VIDEO_MAX_MS)
                }
                when (prepared) {
                    is MediaCompressor.Result.TooLarge -> Pair(null, prepared.message)
                    is MediaCompressor.Result.Error -> Pair(null, "Failed to process media")
                    is MediaCompressor.Result.Success -> when (
                        val sent = MediaSender.send(context, contact, prepared.file, prepared.mimeType, caption)
                    ) {
                        is MediaSender.Result.Failed -> Pair(null, sent.reason)
                        is MediaSender.Result.Sent -> Pair(Triple(sent.msgId, prepared.file, prepared.mimeType), null)
                    }
                }
            }
            val (ok, error) = outcome
            if (ok == null) {
                _toastMessage.emit(error ?: "Failed to send")
                return@launch
            }
            val (msgId, file, mime) = ok
            messageRepo.insertMessage(
                Message(
                    contactId = contactId,
                    body = caption,
                    type = if (mime.startsWith("image")) MessageType.IMAGE else MessageType.VIDEO,
                    isSent = true,
                    mediaUri = file.absolutePath,
                    msgId = msgId,
                    deliveryState = DeliveryState.QUEUED,
                )
            )
            _messages.value = messageRepo.getMessages(contactId)
        }
    }

    fun sendMedia(uri: Uri, mimeType: String, textBody: String, context: Context) {
        val contact = _contact.value ?: return
        if (!contact.canUseInternetTransport) {
            viewModelScope.launch { _toastMessage.emit("Scan this contact's QR code to message them") }
            return
        }
        sendMediaOverInternet(uri, mimeType, textBody, contact, context)
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
