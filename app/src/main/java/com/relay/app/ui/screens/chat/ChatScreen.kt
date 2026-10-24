package com.relay.app.ui.screens.chat

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.relay.app.media.LocalChatMediaPicker
import com.relay.app.media.ChatMediaSource
import com.relay.app.sms.LocationShareService
import com.relay.app.ui.components.ConfirmDialog
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.RelayBottomSheet
import com.relay.app.ui.components.RelayTextField
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ChatScreen(contactId: Long, navController: NavController) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(contactId, app))
    val messages by vm.messages.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val hasOlder by vm.hasOlder.collectAsState()
    val contact by vm.contact.collectAsState()
    val sending by vm.sending.collectAsState()
    val unreadAnchor by vm.unreadAnchorId.collectAsState()
    var actionMessage by remember { mutableStateOf<com.relay.app.data.model.Message?>(null) }
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
    MessageActions(actionMessage, onReply = { message ->
        draftRevision += 1
        replyDraft = com.relay.app.messaging.ReplyBody.draft(message, if (message.isSent) com.relay.app.util.RelayPreferences(app).myName.ifBlank { "Me" } else contact?.name ?: "Someone")
    }, onDismiss = { actionMessage = null })
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (com.relay.app.media.PhoneFiles.sendEnabled) selectedFile = uri
    }
    if (selectedFile != null) ConfirmDialog(title = "Send selected file?", message = "Files up to 100 MiB are encrypted and sent to this direct chat.", confirmLabel = "Send file",
        onConfirm = { selectedFile?.let { vm.sendFile(context, it) }; selectedFile = null }, onDismiss = { selectedFile = null })
    val listState = remember(contactId) { androidx.compose.foundation.lazy.LazyListState() }

    val items = remember(messages, unreadAnchor) {
        ChatTimeline.build(messages, unreadAnchor, System.currentTimeMillis())
    }

    val mediaPicker = LocalChatMediaPicker.current
    val mediaDraft = mediaPicker.draftFor(contactId)
    val pendingMediaUri = mediaDraft?.uri
    val pendingMimeType = mediaDraft?.mime.orEmpty()
    fun consumeAccepted(accepted: AcceptedDraft) {
        if (accepted.composerId != composerId || consumedRevision >= accepted.revision) return
        val remaining = accepted.remainingText(input.text, draftRevision == accepted.revision, appendOnly)
        if (remaining != input.text) input = androidx.compose.ui.text.input.TextFieldValue(remaining,
            androidx.compose.ui.text.TextRange((input.selection.end - (input.text.length - remaining.length)).coerceIn(0, remaining.length)))
        if (replyDraft == accepted.reply) replyDraft = null
        if (accepted.mediaSelectionId != null && mediaPicker.draftFor(contactId)?.selectionId == accepted.mediaSelectionId)
            mediaPicker.clear(contactId)
        consumedRevision = accepted.revision
        draftRevision += 1
    }
    LaunchedEffect(acceptedDraft) { acceptedDraft?.let(::consumeAccepted) }

    var showAttachSheet by remember { mutableStateOf(false) }
    // Both fire something the other person sees; confirming first means a stray tap never sends it.
    var confirmShareLocation by remember { mutableStateOf(false) }
    var confirmRequestLocation by remember { mutableStateOf(false) }

    // Share-my-location: ask for location permission the first time, then share straight away.
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION) { granted ->
        if (granted) contact?.let { LocationShareService.share(context, it) }
    }

    LaunchedEffect(Unit) {
        vm.toastMessage.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    DisposableEffect(Unit) {
        vm.registerSmsUpdates(context)
        onDispose { vm.unregisterSmsUpdates(context) }
    }

    FollowConversationBottom(listState, items.lastOrNull()?.key)

    val lastReceivedTs = messages.filter { !it.isSent }.maxOfOrNull { it.timestamp } ?: 0L
    LaunchedEffect(lastReceivedTs) {
        if (lastReceivedTs > 0L) vm.sendReadReceipt(context, lastReceivedTs)
    }

    if (showAttachSheet) {
        AttachSheet(
            onDismiss = { showAttachSheet = false },
            onFile = if (com.relay.app.media.PhoneFiles.sendEnabled) ({ showAttachSheet = false; filePicker.launch(arrayOf("*/*")) }) else null,
            onGif = { showAttachSheet = false; mediaPicker.open(contactId, ChatMediaSource.GIF) },
            onPhoto = {
                showAttachSheet = false
                mediaPicker.open(contactId, ChatMediaSource.PHOTO)
            },
            onVideo = {
                showAttachSheet = false
                mediaPicker.open(contactId, ChatMediaSource.VIDEO)
            },
            onCamera = {
                showAttachSheet = false
                mediaPicker.open(contactId, ChatMediaSource.CAMERA)
            },
        )
    }

    if (confirmShareLocation) {
        ConfirmDialog(
            title = "Share your location with ${contact?.name ?: "them"}?",
            message = "They will see where you are right now. Sharing stops after it expires, per your location settings.",
            confirmLabel = "Share location",
            onConfirm = {
                confirmShareLocation = false
                val c = contact
                if (c != null) {
                    if (locationPermission.status.isGranted) LocationShareService.share(context, c)
                    else locationPermission.launchPermissionRequest()
                }
            },
            onDismiss = { confirmShareLocation = false },
        )
    }

    if (confirmRequestLocation) {
        ConfirmDialog(
            title = "Ask ${contact?.name ?: "them"} for their location?",
            message = "They will be asked to share where they are, and can decline.",
            confirmLabel = "Ask",
            onConfirm = { confirmRequestLocation = false; vm.sendLocationRequest(context) },
            onDismiss = { confirmRequestLocation = false },
        )
    }

    Scaffold(
        // The top bar and composer already handle the status/navigation-bar insets themselves
        // (statusBarsPadding / navigationBarsPadding), so the Scaffold must not reserve that space a
        // second time — on a phone with a tall 3-button navigation bar that doubling showed up as a
        // large empty gap above the composer.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                contact = contact,
                onBack = { navController.popBackStack() },
                actions = {
                    ConversationMenu(contactId,
                        onShare = if(contact?.isDeleted != true) ({ confirmShareLocation = true }) else null,
                        onRequest = if(contact?.isDeleted != true) ({ confirmRequestLocation = true }) else null)

                },
            )
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!loaded) {
                com.relay.app.ui.components.InitialLoadState(loadError, vm::reload, Modifier.weight(1f).fillMaxWidth())
            } else if (items.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    title = "Say hello",
                    message = "Messages in this chat are end-to-end encrypted.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md),
                ) {
                    items(items.reversed(), key = { it.key }) { item -> ChatItemRow(item, contactName = contact?.name ?: "", onReply = { message -> actionMessage = message } ) }
                    if(hasOlder) item(key = "load_older") { androidx.compose.material3.TextButton(onClick=vm::loadOlder) { Text("Load older messages") } }
                }
            }

            ConversationCaptureNotice(contact = contactId, targets = {
                val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                messages.filter { it.type == com.relay.app.data.model.MessageType.IMAGE && "m_${it.id}" in visibleKeys }
                    .mapNotNull { it.msgId }.take(8)
            })
            if (contact?.isDeleted == true) {
                Text(
                    "You deleted this contact. You can't send new messages here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface1)
                        .navigationBarsPadding()
                        .padding(RelaySpacing.lg),
                )
            } else {
                ConversationTyping(inputText, contactId = contactId)
                ReplyPreview(replyDraft) { draftRevision += 1; replyDraft = null }
                Composer(
                    value = input,
                    placeholder = if (sending) "Preparing message…" else "Message",
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
                        input = if (next.text.length <= 8000) next else next.copy(text = next.text.take(8000), selection = androidx.compose.ui.text.TextRange(8000))
                    },
                    pendingMediaUri = pendingMediaUri,
                    pendingMediaMime = pendingMimeType,
                    onClearMedia = { mediaPicker.clear(contactId) },
                    onAttach = { showAttachSheet = true },
                    sending = sending,
                    onSend = {
                        if (!vm.sending.value && !(vm.acceptedDraft.value?.let { it.composerId == composerId && it.revision == draftRevision } ?: false) && (inputText.isNotBlank() || mediaDraft != null)) {
                            val revision = draftRevision
                            appendOnly = true
                            val submittedText = input.text
                            val submittedReply = replyDraft
                            val submittedMedia = mediaDraft
                            val body = com.relay.app.messaging.ReplyBody.withText(replyDraft, inputText.trim())
                            val accepted = {
                                val accepted = AcceptedDraft(composerId, revision, submittedMedia?.selectionId, submittedText, submittedReply)
                                vm.acceptedDraft.value = accepted
                                consumeAccepted(accepted)
                            }
                            if (submittedMedia != null) vm.sendMedia(submittedMedia.uri, submittedMedia.mime, body, context, accepted)
                            else vm.sendText(body, context, accepted)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AttachSheet(onDismiss: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit, onCamera: () -> Unit, onGif: () -> Unit = onPhoto, onFile: (() -> Unit)? = null) {
    RelayBottomSheet(onDismiss = onDismiss) {
        if (com.relay.app.media.PhoneFiles.sendEnabled && onFile != null) ListRow(headline = "File (up to 100 MiB)", onClick = onFile, leading = { AttachIcon(Icons.Outlined.AttachFile) })
        ListRow(headline = "Animated GIF / sticker", onClick = onGif, leading = { AttachIcon(Icons.Outlined.Image) })
        ListRow(headline = "Photo from gallery", onClick = onPhoto, leading = { AttachIcon(Icons.Outlined.Image) })
        ListRow(headline = "Video from gallery", onClick = onVideo, leading = { AttachIcon(Icons.Outlined.VideoLibrary) })
        ListRow(headline = "Take a photo", onClick = onCamera, leading = { AttachIcon(Icons.Outlined.CameraAlt) })
    }
}

@Composable
private fun AttachIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, tint = TextSecondary)
}

/** Attach, a pill-shaped text field and a send button; shows a preview of a pending photo above. */
@Composable
internal fun Composer(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSend: () -> Unit,
    pendingMediaUri: Uri? = null,
    pendingMediaMime: String = "",
    onClearMedia: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    placeholder: String = "Message",
    sending: Boolean = false,
) {
    val canSend = !sending && (pendingMediaUri != null || value.text.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .navigationBarsPadding()
            .padding(horizontal = RelaySpacing.md, vertical = RelaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
    ) {
        if (pendingMediaUri != null) {
            Box(modifier = Modifier.size(72.dp)) {
                if (pendingMediaMime.startsWith("video/")) {
                    Box(Modifier.size(72.dp).background(Surface2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VideoLibrary, contentDescription = "Attachment preview", tint = Accent)
                    }
                } else AsyncImage(
                    model = pendingMediaUri,
                    contentDescription = "Attachment preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                )
                IconButton(
                    onClick = onClearMedia,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.TopEnd)
                        // A dark scrim over a photo, whatever the theme.
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove attachment", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
        ) {
            if (onAttach != null) IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach a photo or video",
                    tint = if (pendingMediaUri != null) Accent else TextSecondary,
                )
            }
            RelayTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Accent else Surface2),
            ) {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (canSend) OnAccent else TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
