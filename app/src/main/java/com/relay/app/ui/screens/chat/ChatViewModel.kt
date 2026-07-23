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
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.mms.MediaCompressor
import com.relay.app.mms.MmsSender
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
        }
    }

    fun registerSmsUpdates(context: Context) {
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
        smsUpdateReceiver?.let { context.unregisterReceiver(it) }
        smsUpdateReceiver = null
    }

    fun sendText(body: String, context: Context) {
        val contact = _contact.value ?: return
        viewModelScope.launch {
            val ts = System.currentTimeMillis()
            val sent = RelaySecureSend.send(context, contactRepo, contact, body)
            if (!sent) _toastMessage.emit("Message failed to send")
            val msg = Message(
                contactId = contactId,
                body = body,
                type = MessageType.TEXT,
                isSent = true,
                timestamp = ts,
                msgId = ts.toString(),
            )
            messageRepo.insertMessage(msg)
            _messages.value = messageRepo.getMessages(contactId)
        }
    }

    fun sendLocationRequest(context: Context) {
        val contact = _contact.value ?: return
        viewModelScope.launch {
            val body = SmsMessageParser.LOCATION_REQUEST_MSG
            val sent = RelaySecureSend.send(context, contactRepo, contact, body)
            if (!sent) _toastMessage.emit("Location request failed to send")
            val msg = Message(
                contactId = contactId,
                body = body,
                type = MessageType.LOCATION_REQUEST,
                isSent = true,
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

    fun sendMedia(uri: Uri, mimeType: String, textBody: String, context: Context) {
        val contact = _contact.value ?: return
        val phone = contact.phone
        viewModelScope.launch {
            if (!MmsSender.isNetworkAvailable(context)) {
                _toastMessage.emit("Media requires a connection")
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                if (mimeType.startsWith("image")) {
                    MediaCompressor.compressImage(context, uri)
                } else {
                    MediaCompressor.prepareVideo(context, uri)
                }
            }

            when (result) {
                is MediaCompressor.Result.TooLarge -> {
                    _toastMessage.emit(result.message)
                }
                is MediaCompressor.Result.Error -> {
                    _toastMessage.emit("Failed to process media")
                }
                is MediaCompressor.Result.Success -> {
                    val sent = withContext(Dispatchers.IO) {
                        MmsSender.sendMms(
                            context, phone, result.file, result.mimeType,
                            textBody.ifBlank { null },
                            contact.publicKey,
                        )
                    }
                    if (sent) {
                        val type = if (result.mimeType.startsWith("image")) MessageType.IMAGE else MessageType.VIDEO
                        val msg = Message(
                            contactId = contactId,
                            body = textBody.ifBlank { "" },
                            type = type,
                            isSent = true,
                            mediaUri = result.file.absolutePath,
                        )
                        messageRepo.insertMessage(msg)
                        _messages.value = messageRepo.getMessages(contactId)
                    } else {
                        _toastMessage.emit("Failed to send MMS")
                    }
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val contactId: Long,
    private val app: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(contactId, app) as T
    }
}
