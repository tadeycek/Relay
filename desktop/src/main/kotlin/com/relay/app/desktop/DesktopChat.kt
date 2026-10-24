package com.relay.app.desktop

import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything [DesktopChat] reads to build a window state. A seam so tests can count lookups and prove
 * which thread they run on; the production implementation is a plain pass-through to the engine.
 */
interface ChatReads {
    fun ownKey(): String
    fun ownName(): String
    fun ownAvatar(): ByteArray?
    fun status(): TransportStatus
    fun conversations(): List<DesktopConversation>
    fun groups(): Map<String, StoredGroup>
    fun contacts(): List<DesktopContact>
    /** Every stored profile, once per refresh. Per-row lookups would each take the database lock. */
    fun profiles(): Map<String, DesktopProfile>
    fun lastMessage(conversationId: String): DesktopMessage?
    fun unreadCount(conversationId: String): Int
    fun messages(conversationId: String): List<DesktopMessage>
    fun readThrough(conversationId: String): Long
    fun markRead(conversationId: String, through: Long)
    fun conversationExists(conversationId: String): Boolean
    fun draft(conversationId: String): String
    fun setDraft(conversationId: String, text: String)
    fun meta(name: String): String?
    fun setMeta(name: String, value: String)
}

class EngineChatReads(private val engine: DesktopEngine) : ChatReads {
    private val store get() = engine.store
    override fun ownKey() = engine.identity.publicKeyHex()
    override fun ownName() = engine.profiles.ownName()
    override fun ownAvatar() = engine.profiles.ownAvatar()
    override fun status() = engine.status.value
    override fun conversations() = engine.conversations()
    override fun groups() = store.storedGroups().associateBy { it.conversationId }
    override fun contacts() = engine.contacts()
    override fun profiles() = store.profiles().associateBy { it.pubkey }
    override fun lastMessage(conversationId: String) = store.lastMessage(conversationId)
    override fun unreadCount(conversationId: String) = store.unreadCount(conversationId)
    override fun messages(conversationId: String) = engine.messages(conversationId)
    override fun readThrough(conversationId: String) = store.readThrough(conversationId)
    override fun markRead(conversationId: String, through: Long) { store.markRead(conversationId, through) }
    override fun conversationExists(conversationId: String) = store.conversation(conversationId) != null
    override fun draft(conversationId: String) = store.conversationDraft(conversationId)
    override fun setDraft(conversationId: String, text: String) { store.setConversationDraft(conversationId, text) }
    override fun meta(name: String) = store.meta(name)
    override fun setMeta(name: String, value: String) { store.setMeta(name, value) }
}

/**
 * The window's state machine. It is the only place that turns the store into [ChatUiState], so the
 * composables stay dumb and the three-pane behaviour is testable without a display.
 *
 * Store work (SQLite behind a lock) runs on [io]; state is published from [scope] (the UI dispatcher in
 * the app). Every refresh takes a generation number and only the newest request may publish, so a slow
 * older query can never overwrite a newer state.
 *
 * Survives restart: the selected conversation (`ui.selected`) and each conversation's draft are stored
 * in the encrypted database. Survives resize: nothing here depends on window dimensions.
 */
class DesktopChat(
    private val engine: DesktopEngine,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val reads: ChatReads = EngineChatReads(engine),
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    @Volatile private var selectedId: String? = null
    @Volatile private var error: String? = null
    @Volatile private var draftVersion = 0L
    private val generation = AtomicLong()

    private val draftLock = Any()
    private val draftFence = HashMap<String, Long>()
    private val draftJobs = HashMap<String, Job>()

    /**
     * Runs a controller job without letting a storage failure escape as an uncaught exception (which
     * would take the window down). After the scope is cancelled (window closing) failures are ignored.
     */
    private fun launchSafely(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            if (isActive) _state.value = _state.value.copy(error = e.message ?: "Something went wrong reading local data")
        }
    }

    /** Restores the last selected conversation, loads the rail, then follows engine revisions. */
    fun start() {
        launchSafely {
            val gen = generation.incrementAndGet()
            val snapshot = withContext(io) {
                selectedId = reads.meta(SELECTED)?.takeIf { reads.conversationExists(it) }
                build()
            }
            publish(gen, snapshot)
            engine.revision.collect { refresh() }
        }
    }

    /** Rebuilds the state off the UI thread. Safe to call from any thread, as often as needed. */
    fun refresh() = launchRefresh()

    private fun launchRefresh(prepare: () -> Unit = {}) {
        val gen = generation.incrementAndGet()
        launchSafely {
            val snapshot = withContext(io) { prepare(); build() }
            publish(gen, snapshot)
        }
    }

    private fun publish(gen: Long, snapshot: ChatUiState) {
        // Only the newest request publishes; an older, slower query is discarded.
        if (gen == generation.get()) _state.value = snapshot
    }

    private fun build(): ChatUiState {
        val own = reads.ownKey()
        val profiles = reads.profiles()
        val groups = reads.groups()
        val contacts = reads.contacts().associateBy { it.nostrPubkey ?: it.key }
        val short = { key: String -> key.take(8) + "…" + key.takeLast(6) }
        val rail = reads.conversations().map { conversation ->
            val isGroup = isGroupConversation(conversation.id)
            val group = groups[conversation.id]
            val peerProfile = if (isGroup) null else profiles[conversation.id]
            val title = DesktopUi.railTitle(conversation.id, group, peerProfile, contacts[conversation.id]?.name, short(conversation.id))
            val last = reads.lastMessage(conversation.id)
            val senderName = last?.let { message ->
                if (message.outgoing) "You"
                else if (isGroup) group?.members?.firstOrNull { it.key == message.sender }?.name
                    ?: profiles[message.sender]?.name?.takeIf { it.isNotBlank() } ?: short(message.sender)
                else title
            }
            RailItem(
                id = conversation.id,
                title = title,
                subtitle = if (isGroup) {
                    val count = group?.members?.size ?: 0
                    if (count == 1) "1 member" else "$count members"
                } else {
                    short(conversation.id)
                },
                isGroup = isGroup,
                unread = reads.unreadCount(conversation.id),
                lastMessage = last?.body,
                lastSender = senderName,
                lastTs = last?.ts ?: conversation.updatedAt,
                avatar = group?.avatar ?: peerProfile?.avatar,
                memberCount = group?.members?.size,
            )
        }.let(DesktopUi::sortedRail)

        var chosen = selectedId
        if (chosen != null && rail.none { it.id == chosen }) chosen = null
        // Open the most recent conversation on first launch, so the window is never a dead pane.
        if (chosen == null && rail.isNotEmpty()) {
            chosen = rail.first().id
            reads.setMeta(SELECTED, chosen)
        }
        if (selectedId != chosen) selectedId = chosen
        val current = rail.firstOrNull { it.id == chosen }
        val group = current?.let { groups[it.id] }
        val messages = current?.let { reads.messages(it.id) }.orEmpty()
        val thread = current?.let { item ->
            DesktopUi.buildThread(
                messages = messages,
                ownPubkey = own,
                nameOf = { key ->
                    if (item.isGroup) {
                        group?.members?.firstOrNull { it.key == key }?.name
                            ?: profiles[key]?.name?.takeIf { it.isNotBlank() } ?: short(key)
                    } else profiles[key]?.name?.takeIf { it.isNotBlank() } ?: item.title
                },
                avatarOf = { key -> profiles[key]?.avatar },
                now = now(),
            )
        }.orEmpty()

        if (current != null) {
            val newest = messages.maxOfOrNull { it.ts } ?: 0L
            if (newest > reads.readThrough(current.id)) reads.markRead(current.id, newest)
        }

        val status = reads.status()
        return ChatUiState(
            rail = rail.map { if (it.id == chosen && it.unread > 0) it.copy(unread = 0) else it },
            selected = current?.copy(unread = 0),
            thread = thread,
            members = group?.let { DesktopUi.memberRows(it, own) { key -> profiles[key] } }.orEmpty(),
            selfName = reads.ownName(),
            selfAvatar = reads.ownAvatar(),
            draft = current?.let { reads.draft(it.id) } ?: "",
            draftVersion = draftVersion,
            status = statusLabel(status),
            offline = status == TransportStatus.STOPPED,
            canSend = current != null,
            error = error,
        )
    }

    private fun statusLabel(status: TransportStatus): String = when (status) {
        TransportStatus.ONLINE -> "Online"
        TransportStatus.CONNECTING -> "Connecting…"
        TransportStatus.OFFLINE -> "Offline — reconnecting"
        TransportStatus.WAITING_FOR_TOR -> "Waiting for Tor"
        TransportStatus.STOPPED -> "Offline — no mailbox configured"
    }

    // ---- actions -------------------------------------------------------------------------------

    fun select(id: String) {
        selectedId = id
        error = null
        launchRefresh {
            reads.setMeta(SELECTED, id)
            reads.markRead(id, reads.messages(id).maxOfOrNull { it.ts } ?: 0L)
        }
    }

    /** Typing must not block the UI thread on SQLite; writes are coalesced and fenced. */
    fun draftChanged(text: String) {
        val id = selectedId ?: return
        val version = synchronized(draftLock) {
            draftJobs.remove(id)?.cancel()
            ((draftFence[id] ?: 0L) + 1).also { draftFence[id] = it }
        }
        val job = launchSafely {
            delay(250)
            withContext(io) {
                synchronized(draftLock) { if (draftFence[id] == version) reads.setDraft(id, text) }
            }
        }
        synchronized(draftLock) { draftJobs[id] = job }
    }

    fun send(text: String) {
        val id = selectedId ?: return
        if (text.isBlank()) return
        error = null
        // Fence out any pending debounce write: it carries the text we are about to send, and landing
        // after a successful send would resurrect it in the composer.
        val fence = synchronized(draftLock) {
            draftJobs.remove(id)?.cancel()
            ((draftFence[id] ?: 0L) + 1).also { draftFence[id] = it }
        }
        launchSafely {
            val result = withContext(io) { engine.sendText(id, text) }
            withContext(io) {
                synchronized(draftLock) {
                    // Someone typing after the send started owns the draft; do not touch it.
                    val untouched = draftFence[id] == fence
                    when (result) {
                        is com.relay.app.transport.SendResult.Sent -> if (untouched) reads.setDraft(id, "")
                        is com.relay.app.transport.SendResult.Failed -> if (untouched) reads.setDraft(id, text)
                    }
                }
            }
            if (result is com.relay.app.transport.SendResult.Failed) {
                error = result.reason
                draftVersion++
            }
            refresh()
        }
    }

    fun setOwnName(name: String) {
        error = null
        launchSafely {
            runCatching { withContext(io) { engine.profiles.publishName(name) } }.onFailure { error = it.message }
            refresh()
        }
    }

    fun setOwnAvatar(jpeg: ByteArray?) {
        error = null
        launchSafely {
            runCatching { withContext(io) { engine.profiles.publishAvatar(jpeg) } }.onFailure { error = it.message }
            refresh()
        }
    }

    fun dismissError() {
        error = null
        refresh()
    }

    companion object {
        private const val SELECTED = "ui.selected"
    }
}
