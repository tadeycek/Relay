package com.relay.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextSecondary

/** Group messages share the 1:1 bubbles; the sender's contact id lets the timeline tell members apart. */
private fun GroupMessage.asMessage() = Message(
    id = id,
    contactId = senderKey?.let { com.relay.app.groups.GroupProtocol.memberId(it) } ?: contactId ?: 0L,
    body = body,
    msgId = eventId,
    type = type,
    lat = lat,
    lng = lng,
    isSent = isSent,
    timestamp = timestamp,
    mediaUri = mediaUri,
    pinLabel = pinLabel,
    expiryAt = expiryAt,
    unread = unread,
    deliveryState = deliveryState,
)

@Composable
fun GroupChatScreen(groupId: Long, navController: NavController) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: GroupChatViewModel = viewModel(factory = GroupChatViewModelFactory(groupId, app))
    val group by vm.group.collectAsState()
    val messages by vm.messages.collectAsState()
    val hasOlder by vm.hasOlder.collectAsState()
    val friends by vm.friends.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.error.collectAsState()
    var replyDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val acceptedDraft by vm.acceptedDraft.collectAsState()
    val composerId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    var draftRevision by rememberSaveable { mutableStateOf(0L) }
    var input by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    val inputText = input.text
    var appendOnly by rememberSaveable { mutableStateOf(true) }
    var consumedRevision by rememberSaveable { mutableStateOf(-1L) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    val picker = com.relay.app.media.LocalChatMediaPicker.current
    // Negative group targets cannot collide with positive contact ids.
    val pickerId = -groupId - 1000000L
    val media = picker.draftFor(pickerId)
    fun consumeAccepted(accepted: AcceptedDraft) {
        if (accepted.composerId != composerId || consumedRevision >= accepted.revision) return
        val remaining = accepted.remainingText(input.text, draftRevision == accepted.revision, appendOnly)
        if (remaining != input.text) input = androidx.compose.ui.text.input.TextFieldValue(remaining,
            androidx.compose.ui.text.TextRange((input.selection.end - (input.text.length - remaining.length)).coerceIn(0, remaining.length)))
        if (replyDraft == accepted.reply) replyDraft = null
        if (accepted.mediaSelectionId != null && picker.draftFor(pickerId)?.selectionId == accepted.mediaSelectionId)
            picker.clear(pickerId)
        consumedRevision = accepted.revision
        draftRevision += 1
    }
    LaunchedEffect(acceptedDraft) { acceptedDraft?.let(::consumeAccepted) }

    var showAttach by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var confirmRequestLocation by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val own = remember { com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context) }
    val canSend = group?.sharedOwner != null && group?.members.orEmpty().any { it.nostrPubkey == own }
    MessageActions(actionMessage, groupId, onReply = { message ->
        draftRevision += 1
        replyDraft = com.relay.app.messaging.ReplyBody.draft(message, if (message.isSent) com.relay.app.util.RelayPreferences(context).myName.ifBlank { "Me" } else messages.firstOrNull { it.id == message.id }?.senderName ?: "Someone")
    }, onDismiss = { actionMessage = null })
    if (showAttach) AttachSheet(onDismiss = { showAttach = false },
        onGif = { showAttach = false; picker.open(pickerId, com.relay.app.media.ChatMediaSource.GIF) },
        onPhoto = { showAttach = false; picker.open(pickerId, com.relay.app.media.ChatMediaSource.PHOTO) },
        onVideo = { showAttach = false; picker.open(pickerId, com.relay.app.media.ChatMediaSource.VIDEO) },
        onCamera = { showAttach = false; picker.open(pickerId, com.relay.app.media.ChatMediaSource.CAMERA) })
    if (showMembers) group?.let { current ->
        com.relay.app.ui.screens.contacts.ManageGroupSheet(current, friends, current.sharedOwner == own, own,
            onRemove = { vm.changeMember(context, it, false) }, onAdd = { vm.changeMember(context, it, true) },
            onDeleteGroup = { vm.deleteGroup(context) { navController.popBackStack() } }, onDismiss = { showMembers = false })
    }
    val listState = remember(groupId) { androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(error) { error?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); vm.clearError() } }

    DisposableEffect(Unit) {
        vm.registerUpdates(context)
        onDispose { vm.unregisterUpdates(context) }
    }

    val items = remember(messages) {
        ChatTimeline.build(messages.map { it.asMessage() }, firstUnreadId = null, nowMs = System.currentTimeMillis())
    }
    val names = remember(group, messages) { messages.filter { it.senderKey != null }.associate { com.relay.app.groups.GroupProtocol.memberId(it.senderKey!!) to it.senderName.orEmpty() } + group?.members.orEmpty().associate { it.id to it.name } }

    FollowConversationBottom(listState, items.lastOrNull()?.key)

    Scaffold(
        // See ChatScreen: the top bar and composer already apply their own system-bar insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GroupChatTopBar(
                groupId = groupId,
                name = group?.name.orEmpty(),
                memberSeeds = group?.members.orEmpty().map { GlyphGenerator.seedFor(it.nostrPubkey, null, it.name) },
                memberCount = group?.members?.size ?: 0,
                onBack = { navController.popBackStack() },
                actions = { ConversationMenu(groupId, true, onMembers = { vm.refresh(); showMembers = true }) },

            )
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                    reverseLayout = true,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = RelaySpacing.lg, vertical = RelaySpacing.md),
            ) {
                items(items.reversed(), key = { it.key }) { item ->
                    ChatItemRow(item, groupId = groupId, contactName = "Someone", senderName = { names[it] }, onReply = { message ->
                        actionMessage = message
                    })
                }
                if(hasOlder) item(key = "load_older") { androidx.compose.material3.TextButton(onClick=vm::loadOlder) { androidx.compose.material3.Text("Load older messages") } }
            }
            ConversationCaptureNotice(group = groupId, targets = {
                val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                messages.filter { it.type == com.relay.app.data.model.MessageType.IMAGE && "m_${it.id}" in visibleKeys }
                    .mapNotNull { it.eventId }.take(8)
            })
            if (canSend) {
                ConversationTyping(inputText, groupId = groupId, revisionKey = group)
                ReplyPreview(replyDraft) { draftRevision += 1; replyDraft = null }
                Composer(
                    value = input,
                    onValueChange = { edited ->
                        val accepted = vm.acceptedDraft.value?.takeIf { it.composerId == composerId && it.revision == consumedRevision }
                        val text = accepted?.rebaseKeyboardEdit(inputText,input.text,edited.text) ?: edited.text
                        val removed = edited.text.length - text.length
                        val next = if(removed == 0) edited else edited.copy(text=text,
                            selection=androidx.compose.ui.text.TextRange((edited.selection.end-removed).coerceIn(0,text.length)), composition=null)
                        if (next.text != input.text) {
                            appendOnly = appendOnly && input.selection.collapsed && input.selection.end == input.text.length && next.text.startsWith(input.text)
                            draftRevision += 1
                        }
                        input = if (next.text.length <= 1000) next else next.copy(text = next.text.take(1000), selection = androidx.compose.ui.text.TextRange(1000))
                    },
                    placeholder = if (sending) "Preparing attachment…" else "Message the group",
                    pendingMediaUri = media?.uri,
                    pendingMediaMime = media?.mime.orEmpty(),
                    onClearMedia = { picker.clear(pickerId) },
                    onAttach = { showAttach = true },
                    sending = sending,
                    onSend = {
                        if (!vm.sending.value && !(vm.acceptedDraft.value?.let { it.composerId == composerId && it.revision == draftRevision } ?: false) && (inputText.isNotBlank() || media != null)) {
                            val revision = draftRevision
                            appendOnly = true
                            val submittedText = input.text
                            val submittedReply = replyDraft
                            val sentText = inputText
                            val sentDraft = replyDraft
                            val queued = {
                                val accepted = AcceptedDraft(composerId, revision, media?.selectionId, submittedText, submittedReply)
                                vm.acceptedDraft.value = accepted
                                consumeAccepted(accepted)
                            }
                            val body = com.relay.app.messaging.ReplyBody.withText(sentDraft, sentText.trim())
                            if (media != null) vm.sendMedia(context, media.uri, media.mime, body, queued)
                            else vm.sendText(body, context, queued)
                        }
                    },
                )
            } else androidx.compose.material3.Text(
                if (group?.sharedOwner == null) "This is a legacy local group. Create a new shared group from People." else "You are no longer a member of this group.",
                modifier = Modifier.padding(16.dp), color = TextSecondary)
        }
    }

}
