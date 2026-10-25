package com.relay.app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Files
import java.nio.file.Path


/** Everything the window can ask the controller to do. Kept as data so UI tests can record calls. */
data class ChatActions(
    val select: (String) -> Unit = {},
    val send: (String) -> Unit = {},
    val draftChanged: (String) -> Unit = {},

    val setOwnName: (String) -> Unit = {},
    val setOwnAvatar: (ByteArray?) -> Unit = {},
    val dismissError: () -> Unit = {},
    val attach: () -> Unit = {},
    /** Opens the photo/video picker (WP08). */
    val attachMedia: () -> Unit = {},
)

// Discord's own density, not its logo or typeface: a dark three-pane messenger.
private val RailColor = Color(0xFF1A1B1E)
private val ListColor = Color(0xFF2B2D31)
private val MainColor = Color(0xFF313338)
private val HeaderColor = Color(0xFF2B2D31)
private val TextColor = Color(0xFFDBDEE1)
private val MutedColor = Color(0xFF949BA4)
private val Accent = Color(0xFF5865F2)
private val OutgoingBubble = Color(0xFF4752C4)

/** Decodes a stored JPEG, or null when the bytes are absent/damaged. Never throws into the UI. */
fun avatarImage(bytes: ByteArray?): ImageBitmap? =
    bytes?.takeIf { it.size > 3 }?.let { data ->
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
    }

@Composable
fun RelayAvatar(name: String, bytes: ByteArray?, size: Int = 40) {
    val image = remember(bytes) { avatarImage(bytes) }
    Box(Modifier.size(size.dp).clip(CircleShape).background(avatarColor(name)), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(DesktopUi.initials(name), color = Color.White, fontSize = (size / 2.6).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun avatarColor(name: String): Color {
    val palette = listOf(
        Color(0xFF5865F2), Color(0xFF3BA55D), Color(0xFFFAA81A), Color(0xFFEB459E),
        Color(0xFF00A8FC), Color(0xFFED4245), Color(0xFF9B59B6),
    )
    val hash = name.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    engine: DesktopEngine? = null,
) {
    var quickSwitcher by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
    var field by remember(state.selected?.id) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    var appliedDraftVersion by remember(state.selected?.id) { mutableStateOf(state.draftVersion) }
    val composerFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.selected?.id) { runCatching { composerFocus.requestFocus() } }
    LaunchedEffect(state.thread.size, state.selected?.id) {
        if (state.thread.isNotEmpty()) runCatching { listState.animateScrollToItem(state.thread.lastIndex) }
    }
    // A failed send hands the draft back. Re-apply it as soon as the controller publishes it, but only
    // once per restore and never over text the user has typed since.
    LaunchedEffect(state.selected?.id, state.draft, state.draftVersion) {
        if (state.draftVersion != appliedDraftVersion) {
            appliedDraftVersion = state.draftVersion
            if (field.text.isBlank()) field = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MainColor)
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.isCtrlPressed && event.key == Key.K -> { quickSwitcher = !quickSwitcher; true }

                    event.key == Key.Escape -> {
                        quickSwitcher = false; dialog = null; true
                    }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            GuildRail(state, onSettings = { dialog = ChatDialog.Settings })
            ConversationList(state, actions, modifier = Modifier.width(280.dp), onQuickSwitcher = { quickSwitcher = true })
            Column(Modifier.weight(1f).fillMaxHeight().background(MainColor)) {
                ThreadHeader(state, showMembers, onToggleMembers = { showMembers = !showMembers })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.selected == null) {
                        EmptyPane("No conversation selected", "Pick a chat on the left, or press Ctrl+K to search.")
                    } else if (state.thread.isEmpty()) {
                        EmptyPane("No messages yet", "This is the start of your conversation with ${state.selected.title}.")
                    } else {
                        MessageList(state, listState, engine)
                    }
                }
                state.error?.let { message ->
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF4A2023)).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, color = Color(0xFFF2B8B5), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = actions.dismissError) { Text("Dismiss", color = Color(0xFFF2B8B5), fontSize = 11.sp) }
                    }
                }
                if (state.offline) {
                    Text(
                        "You are offline. Messages cannot be delivered until the mailbox is reachable.",
                        color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
                Composer(state, field, composerFocus, onAttach = actions.attachMedia, onField = { next ->
                    val changed = next.text != field.text
                    field = next
                    if (changed) actions.draftChanged(next.text)
                }, onSend = {
                    val text = field.text
                    if (text.isNotBlank()) {
                        field = TextFieldValue("")
                        actions.send(text)
                    }
                })
            }
            if (showMembers && state.selected?.isGroup == true) {
                MembersPanel(state, Modifier.width(230.dp).fillMaxHeight())
            }
        }

        if (quickSwitcher) {
            QuickSwitcher(state, actions, onClose = { quickSwitcher = false })
        }
        when (val current = dialog) {
            null -> Unit
            ChatDialog.Settings -> SettingsDialog(state, actions, onClose = { dialog = null })

        }
    }
}

private sealed interface ChatDialog {
    data object Settings : ChatDialog

}

@Composable
private fun GuildRail(state: ChatUiState, onSettings: () -> Unit) {
    Column(
        Modifier.width(76.dp).fillMaxHeight().background(RailColor).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(Accent), contentAlignment = Alignment.Center) {
            Text("R", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.width(32.dp).height(1.dp).background(Color(0xFF3F4147)))
        Box(Modifier.testTag("self-avatar").clickable(onClick = onSettings)) {
            RelayAvatar(state.selfName.ifBlank { "You" }, state.selfAvatar, size = 44)
        }
        Text("You", color = MutedColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
            Text("⚙", color = MutedColor, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ConversationList(state: ChatUiState, actions: ChatActions, modifier: Modifier = Modifier, onQuickSwitcher: () -> Unit) {
    Column(modifier.fillMaxHeight().background(ListColor)) {
        Column(Modifier.padding(16.dp)) {
            Text("Messages", color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Ctrl+K to switch", color = MutedColor, fontSize = 10.sp)
        }
        LazyColumn(Modifier.weight(1f).testTag("rail")) {
            items(state.rail, key = { it.id }) { item ->
                RailRow(item, selected = item.id == state.selected?.id, onClick = { actions.select(item.id) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onQuickSwitcher, modifier = Modifier.weight(1f)) {
                Text("Find a conversation", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RailRow(item: RailItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF404249) else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag("rail-item-${item.id}")
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelayAvatar(item.title, item.avatar, size = 38)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title, color = TextColor, fontSize = 14.sp, fontWeight = if (item.unread > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isGroup) {
                    Spacer(Modifier.width(6.dp))
                    Text("GROUP", color = MutedColor, fontSize = 8.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (item.lastTs > 0) DesktopUi.clockLabel(item.lastTs, System.currentTimeMillis()) else "",
                    color = MutedColor, fontSize = 10.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = DesktopUi.preview(item.lastMessage)
                Text(
                    preview.ifEmpty { item.subtitle },
                    color = if (item.unread > 0) TextColor else MutedColor,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (item.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(CircleShape).background(Accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(DesktopUi.badge(item.unread), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadHeader(state: ChatUiState, showMembers: Boolean, onToggleMembers: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(HeaderColor).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = state.selected?.title ?: "Relay"
        Text(if (state.selected?.isGroup == true) "＃" else "@", color = MutedColor, fontSize = 20.sp)
        Spacer(Modifier.width(6.dp))
        Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(state.selected?.subtitle.orEmpty(), color = MutedColor, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(state.status, color = if (state.offline) Color(0xFFED4245) else Color(0xFF3BA55D), fontSize = 11.sp, modifier = Modifier.testTag("status-label"))
        if (state.selected?.isGroup == true) {
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onToggleMembers, modifier = Modifier.testTag("members-toggle")) {
                Text(if (showMembers) "Hide members" else "Members", color = MutedColor, fontSize = 11.sp)
            }

        }
    }
}

@Composable
private fun MessageList(state: ChatUiState, listState: androidx.compose.foundation.lazy.LazyListState, engine: DesktopEngine? = null) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp).testTag("message-list"),
    ) {
        items(state.thread, key = { it.id }) { row -> MessageRow(row, engine) }
    }
}

@Composable
private fun MessageRow(row: ThreadRow, engine: DesktopEngine? = null) {
    Column(Modifier.fillMaxWidth().padding(top = if (row.showHeader) 10.dp else 2.dp)) {
        if (row.showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.testTag("message-avatar-${row.id}")) { RelayAvatar(row.senderName, row.avatar, size = 30) }
                Spacer(Modifier.width(8.dp))
                Text(row.senderName, color = if (row.outgoing) Color(0xFFA8B3FF) else TextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(row.time, color = MutedColor, fontSize = 10.sp)
            }
            Spacer(Modifier.height(2.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(38.dp))
            Surface(
                color = if (row.outgoing) OutgoingBubble else Color(0xFF3F4147),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(0.75f).testTag("message-${row.id}"),
            ) {
                if (row.media && engine != null) MediaBubble(engine, row.id, row.outgoing, row.caption)
                else Text(
                    row.text.ifEmpty { "(empty message)" },
                    color = if (row.outgoing) Color.White else TextColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, field: TextFieldValue, focus: FocusRequester, onAttach: () -> Unit, onField: (TextFieldValue) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MainColor).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextButton(onClick = onAttach, enabled = state.canSend && state.selected?.isGroup != true, modifier = Modifier.testTag("attach-media")) { Text("Photo/video", fontSize = 11.sp) }
        OutlinedTextField(
            value = field,
            onValueChange = onField,
            placeholder = { Text(if (state.selected == null) "Select a conversation" else "Message ${state.selected.title}", fontSize = 13.sp) },
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("composer").onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    if (event.isShiftPressed) {
                        // Insert at the caret (replacing any selection) and keep the caret after the newline.
                        val start = field.selection.min
                        val text = field.text.replaceRange(start, field.selection.max, "\n")
                        onField(TextFieldValue(text, TextRange(start + 1)))
                    } else {
                        onSend()
                    }
                    true
                } else false
            },
            maxLines = 6,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextColor, fontSize = 13.sp),
            enabled = state.canSend,
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onSend,
            enabled = state.canSend && field.text.isNotBlank(),
            modifier = Modifier.testTag("send"),
        ) { Text("Send") }
    }
}

@Composable
private fun EmptyPane(headline: String, detail: String) {
    Box(Modifier.fillMaxSize().testTag("thread-empty"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✉", color = MutedColor, fontSize = 34.sp)
            Spacer(Modifier.height(8.dp))
            Text(headline, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = MutedColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MembersPanel(state: ChatUiState, modifier: Modifier = Modifier) {
    Column(modifier.background(ListColor).padding(14.dp).testTag("members-panel")) {
        Text("Members — ${state.members.size}", color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.members, key = { it.key }) { member ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RelayAvatar(member.name, member.avatar, size = 28)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.name, color = TextColor, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (member.isOwner) Text("Creator", color = MutedColor, fontSize = 9.sp)
                        else if (member.isSelf) Text("You", color = MutedColor, fontSize = 9.sp)
                    }

                }
            }
        }
        Text("Edit groups on your phone", color = MutedColor, fontSize = 11.sp)
    }
}

@Composable
private fun QuickSwitcher(state: ChatUiState, actions: ChatActions, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val results = DesktopUi.filterRail(state.rail, query)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).testTag("quick-switcher"), contentAlignment = Alignment.TopCenter) {
        Surface(color = ListColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.width(460.dp).padding(top = 90.dp)) {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Jump to a conversation…", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("quick-switcher-input")
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Escape -> { onClose(); true }
                                event.key == Key.Enter -> {
                                    results.firstOrNull()?.let { actions.select(it.id); onClose() }
                                    true
                                }
                                else -> false
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(220.dp)) {
                    items(results, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.select(item.id); onClose() }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RelayAvatar(item.title, item.avatar, size = 26)
                            Spacer(Modifier.width(8.dp))
                            Text(item.title, color = TextColor, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(item.subtitle, color = MutedColor, fontSize = 10.sp)
                        }
                    }
                    if (results.isEmpty()) item { Text("No conversation matches.", color = MutedColor, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(state: ChatUiState, actions: ChatActions, onClose: () -> Unit) {
    var name by remember { mutableStateOf(state.selfName) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(onClick = { actions.setOwnName(name); onClose() }, enabled = name.trim().isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Your profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelayAvatar(name, state.selfAvatar, size = 42)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        TextButton(onClick = { pickImage()?.let { actions.setOwnAvatar(it) } }) { Text("Choose a photo…") }
                        Text("JPEG, up to 8 KB — sent only to your contacts.", color = MutedColor, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Relay desktop 0.1.0 · messages are end-to-end encrypted and stored locally for 7 days.\n" +
                        "Licences: Compose Multiplatform, SQLite (public domain), Tink, OkHttp, nostr-sdk — Apache-2.0/MIT.",
                    color = MutedColor, fontSize = 10.sp,
                )
            }
        },
    )
}

/** Native picker on the AWT thread; any failure (no portal, cancelled) is just "no picture". */
private fun pickImage(): ByteArray? = try {
    val chosen: Path? = desktopFileDialog(false)
    chosen?.let { Files.readAllBytes(it).takeIf { bytes -> bytes.size <= 8 * 1024 } }
} catch (e: Exception) {
    null
}
