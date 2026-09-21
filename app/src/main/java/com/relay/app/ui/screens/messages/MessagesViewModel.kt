package com.relay.app.ui.screens.messages

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.data.conversation.ConversationSummary
import com.relay.app.data.repository.ConversationRepository
import com.relay.app.messaging.Broadcasts
import com.relay.app.messaging.MessagingDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the Messages tab and the unread badge on the bottom bar. Reloads whenever a message
 * arrives, its delivery state changes, or a chat is opened (which clears its unread count).
 */
class MessagesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConversationRepository(MessagingDb.get(app))

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** False until the first query returns, so the empty state is not flashed while loading. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    init {
        val filter = IntentFilter().apply { Broadcasts.ALL_CONVERSATION_EVENTS.forEach { addAction(it) } }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _conversations.value = repo.getConversations()
            _loaded.value = true
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        super.onCleared()
    }
}

/** Total unread messages, for the badge on the Messages tab (lives as long as the activity). */
class UnreadViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConversationRepository(MessagingDb.get(app))

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter().apply { Broadcasts.ALL_CONVERSATION_EVENTS.forEach { addAction(it) } }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _total.value = repo.totalUnread() }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        super.onCleared()
    }
}
