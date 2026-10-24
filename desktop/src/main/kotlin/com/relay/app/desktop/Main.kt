package com.relay.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.relay.app.transport.SendResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * The desktop shell: a link gate, then the conversation list, thread and composer.
 *
 * Until this computer links to a phone the only screen is "Link this computer": a scannable QR of the
 * `RELAYLINK:2:…` string (or the grouped one-time code when no encoder is available) plus the
 * waiting / expired / failed states [DesktopLinkFlow] produces. A completed link persists the phone's
 * identity, the enrolled device id and the device-auth key, and the shell rebuilds its mailbox stack
 * so requests are signed as that device.
 */
fun main(args: Array<String>) {
    val options = Args.parse(args)
    val paths = DesktopPaths(options.configDir?.let(Path::of), options.dataDir?.let(Path::of))
    val engine = DesktopEngine.create(paths, options.mailboxUrl, options.peerInnerKeys)
    if (options.printIdentity) {
        println("pubkey=${engine.identity.publicKeyHex()}")
        println("inner=${engine.keyMaterial.publicKeyBase64()}")
        println("linked=${engine.isLinked()}")
        engine.linkedDevice()?.let { println("device=${it.deviceId} label=${it.deviceLabel}") }
        engine.close()
        return
    }

    if (options.sendTestMessage) {
        val target = options.sendTo ?: error("--send-test-message requires a target pubkey")
        val targetInner = options.sendToInner ?: error("--send-test-message requires the target inner key")
        kotlinx.coroutines.runBlocking {
            engine.start()
            val result = engine.sendText(target, "Hello from the peer!")
            println("send-result=$result")
            engine.stop()
        }
        engine.close()
        return
    }

    // The UI owns engine lifetime (a completed link replaces the keys and the transport).
    engine.close()

    application {
        // The link card (QR + code + status + actions) is ~700 px tall; the default 800x600 window clipped
        // and squeezed its action buttons until their labels vanished. Size and floor are explicit.
        Window(
            onCloseRequest = { exitApplication() },
            title = "Relay",
            state = androidx.compose.ui.window.rememberWindowState(size = androidx.compose.ui.unit.DpSize(900.dp, 800.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(760, 740)
            RelayTheme {
                Root(paths, options.mailboxUrl, options.peerInnerKeys, options.initialPeer)
            }
        }
    }
}

private data class Args(
    val mailboxUrl: String,
    val initialPeer: String?,
    val configDir: String?,
    val dataDir: String?,
    val peerInnerKeys: Map<String, String>,
    val printIdentity: Boolean = false,
    val sendTestMessage: Boolean = false,
    val sendTo: String? = null,
    val sendToInner: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): Args {
            var mailboxUrl = System.getenv("RELAY_MAILBOX_URL").orEmpty()
            var initialPeer: String? = System.getenv("RELAY_INITIAL_PEER")
            var configDir: String? = null
            var dataDir: String? = null
            var printIdentity = false
            var sendTestMessage = false
            var sendTo: String? = null
            var sendToInner: String? = null
            val peerKeys = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--mailbox-url" -> mailboxUrl = args.getOrNull(++i).orEmpty()
                    "--peer" -> initialPeer = args.getOrNull(++i)
                    "--config-dir" -> configDir = args.getOrNull(++i)
                    "--data-dir" -> dataDir = args.getOrNull(++i)
                    "--peer-inner-key" -> {
                        val value = args.getOrNull(++i).orEmpty()
                        val peer = args.getOrNull(++i)
                        if (peer != null && value.isNotEmpty()) peerKeys[peer] = value
                    }
                    "--print-identity" -> printIdentity = true
                    "--send-test-message" -> {
                        sendTestMessage = true
                        sendTo = args.getOrNull(++i)
                        sendToInner = args.getOrNull(++i)
                    }
                    "--help" -> {
                        println(
                            "usage: relay-desktop [--mailbox-url URL] [--peer HEX] " +
                                "[--peer-inner-key BASE64 PEER] [--config-dir DIR] [--data-dir DIR] " +
                                "[--print-identity] [--send-test-message PUBKEY INNERKEY]",
                        )
                        kotlin.system.exitProcess(0)
                    }
                }
                i++
            }
            return Args(mailboxUrl, initialPeer, configDir, dataDir, peerKeys, printIdentity, sendTestMessage, sendTo, sendToInner)
        }
    }
}

private val RelayColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF10233F),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF1A1D23),
    onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF23272F),
    onSurfaceVariant = Color(0xFFB4B8C0),
)

@Composable
private fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RelayColors, content = content)
}

/**
 * Owns the engine's lifetime. Linking writes new keys, so on success the stack is rebuilt
 * ([generation] increments) and the transport picks up the device-auth key and `?device=<id>`.
 */
@Composable
private fun Root(paths: DesktopPaths, mailboxUrl: String, peerInnerKeys: Map<String, String>, initialPeer: String?) {
    var generation by remember { mutableStateOf(0) }
    val engine = remember(generation) {
        DesktopEngine.create(paths, mailboxUrl, peerInnerKeys).also { if (mailboxUrl.isNotBlank()) it.start() }
    }
    DisposableEffect(engine) { onDispose { engine.close() } }

    if (engine.isLinked()) {
        App(engine, initialPeer)
    } else {
        LinkScreen(engine, mailboxUrl, onLinked = { generation++ })
    }
}

// ---- link screen -------------------------------------------------------------------------------

@Composable
private fun LinkScreen(engine: DesktopEngine, mailboxUrl: String, onLinked: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A new generation means a new one-time code (the old one may be expired or the user asked).
    var codeGeneration by remember { mutableStateOf(0) }
    val flow = remember(codeGeneration) {
        engine.createLinkFlow(mailboxUrl.ifBlank { "http://127.0.0.1:8791" })
    }
    var state by remember(codeGeneration) { mutableStateOf<LinkState>(flow.begin()) }
    var copied by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Poll the slot while the QR is on screen; cancelled when the flow (the code) changes.
    LaunchedEffect(flow) {
        while (true) {
            val next = flow.step()
            state = next
            if (next !is LinkState.Waiting) break
            delay(DesktopLinkFlow.DEFAULT_POLL_MS)
        }
    }
    LaunchedEffect(flow) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(state) {
        if (state is LinkState.Linked) {
            delay(1_200)
            onLinked()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.width(580.dp),
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Link this computer", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "On your phone open Relay → Settings → Linked computers → Link a computer, then scan " +
                        "this code. It links once and expires in ten minutes.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                when (val current = state) {
                    is LinkState.Linked -> LinkedPanel(current)

                    is LinkState.Expired -> {
                        Notice("This code has expired.", "Nothing was imported. Show a new code and scan it from the phone.")
                        Button(onClick = { codeGeneration++ }) { Text("Show a new code") }
                    }

                    is LinkState.Failed -> {
                        Notice(current.message, "The phone's bundle was not imported.")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                // Same code: the slot is still there when the commit (not the fetch)
                                // failed, so a retry needs no rescan.
                                scope.launch { state = flow.step() }
                            }) { Text("Try again") }
                            OutlinedButton(onClick = { codeGeneration++ }) { Text("Show a new code") }
                        }
                    }

                    else -> {
                        val expiresAt = when (current) {
                            is LinkState.Showing -> current.expiresAt
                            is LinkState.Waiting -> current.expiresAt
                            else -> 0L
                        }
                        val attempts = (current as? LinkState.Waiting)?.attempts ?: 0
                        QrPanel(
                            qrText = flow.qrText.orEmpty(),
                            humanCode = flow.humanCode.orEmpty(),
                            secondsLeft = ((expiresAt - nowMs).coerceAtLeast(0L)) / 1000,
                            attempts = attempts,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                copyToClipboard(flow.qrText.orEmpty())
                                copied = true
                            }) { Text(if (copied) "Copied" else "Copy link code") }
                            OutlinedButton(onClick = { codeGeneration++ }) { Text("New code") }
                        }
                        Text(
                            "This computer: ${flow.deviceLabel.orEmpty()}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrPanel(qrText: String, humanCode: String, secondsLeft: Long, attempts: Int) {
    val matrix = remember(qrText) { qrMatrix(qrText) }
    if (matrix != null) {
        Surface(color = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(264.dp)) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val modules = matrix.width
                val cell = size.minDimension / modules
                for (y in 0 until modules) {
                    for (x in 0 until modules) {
                        if (matrix.get(x, y)) {
                            drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                        }
                    }
                }
            }
        }
    } else {
        Text(
            "QR image not rendered on this build (no encoder available). Type the code below on the phone.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    Text(
        desktopGroupedCode(humanCode),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            if (attempts <= 1) "Waiting for the phone…" else "Waiting for the phone… (${attempts} checks)",
            fontSize = 12.sp,
        )
        Text(
            "expires in ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(headline: String, detail: String) {
    Text(
        headline,
        color = MaterialTheme.colorScheme.error,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun LinkedPanel(linked: LinkState.Linked) {
    Text("Linked", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("This computer is now ${linked.deviceLabel}.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(shortKey(linked.identityPubkeyHex), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Text(
        "Imported ${linked.contactsInserted + linked.contactsUpdated} contacts" +
            (if (linked.groups > 0) ", ${linked.groups} groups" else "") +
            (if (linked.settingsApplied) " and settings" else "") + ".",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The QR module matrix, or null when encoding fails (the code is still shown as text). */
private fun qrMatrix(text: String): com.google.zxing.common.BitMatrix? {
    if (text.isBlank()) return null
    return runCatching {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        )
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }.getOrNull()
}

/** Four groups of eight per line, for reading out; the QR carries the ungrouped string. */
private fun desktopGroupedCode(humanCode: String): String =
    humanCode.split('-').chunked(4).joinToString("\n") { it.joinToString(" ") }

private fun copyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

// ---- conversations -----------------------------------------------------------------------------

@Composable
private fun App(engine: DesktopEngine, initialPeer: String?) {
    var conversations by remember { mutableStateOf(engine.conversations()) }
    var selectedPeer by remember { mutableStateOf(initialPeer) }
    var messages by remember { mutableStateOf(if (initialPeer != null) engine.messages(initialPeer) else emptyList()) }
    var newPeer by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf(engine.contacts()) }
    val scope = rememberCoroutineScope()
    val linked = engine.linkedDevice()

    fun refresh() {
        conversations = engine.conversations()
        contacts = engine.contacts()
        selectedPeer?.let { messages = engine.messages(it) }
    }

    LaunchedEffect(engine.revision) {
        // Re-run whenever the engine commits a message.
        engine.revision.collect { refresh() }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Relay", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (linked != null) {
                Text("Linked to ${shortKey(linked.identityPubkeyHex)}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    "This computer: ${linked.deviceLabel}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Conversations", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    val selected = conversation.id == selectedPeer
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedPeer = conversation.id
                            messages = engine.messages(conversation.id)
                        },
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(shortKey(conversation.id), fontWeight = FontWeight.Medium)
                            Text(
                                conversation.title.takeIf { it != conversation.peer } ?: "Direct message",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPeer,
                    onValueChange = { newPeer = it.trim() },
                    label = { Text("Peer pubkey") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val peer = newPeer.trim()
                    if (peer.length == 64 && peer.all { it in "0123456789abcdef" }) {
                        engine.store.ensureConversation(peer)
                        selectedPeer = peer
                        messages = engine.messages(peer)
                        newPeer = ""
                        refresh()
                    } else {
                        error = "A peer pubkey is 64 lowercase hex characters"
                    }
                }) { Text("Add") }
            }
            Text(
                "Messages: ${messages.size} · Contacts: ${contacts.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (contacts.isNotEmpty()) {
                Text("From your phone", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.height(140.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(contacts, key = { it.key }) { contact ->
                        Text(
                            contact.name + if (contact.nostrPubkey != null) " · Relay" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedPeer?.let(::shortKey) ?: "No conversation selected",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when (engine.status.value) {
                        com.relay.app.transport.TransportStatus.ONLINE -> "Online"
                        com.relay.app.transport.TransportStatus.CONNECTING -> "Connecting…"
                        com.relay.app.transport.TransportStatus.OFFLINE -> "Offline"
                        com.relay.app.transport.TransportStatus.STOPPED -> "Offline"
                        else -> "Offline"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Write a message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    enabled = draft.isNotBlank() && selectedPeer != null &&
                        engine.status.value != com.relay.app.transport.TransportStatus.STOPPED,
                    onClick = {
                        val peer = selectedPeer ?: return@Button
                        val text = draft
                        draft = ""
                        error = null
                        scope.launch {
                            when (val result = engine.sendText(peer, text)) {
                                is SendResult.Sent -> refresh()
                                is SendResult.Failed -> error = result.reason
                            }
                        }
                    },
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: DesktopMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(420.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    message.body,
                    color = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (message.outgoing) "You" else shortKey(message.sender),
                    fontSize = 10.sp,
                    color = if (message.outgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun shortKey(hex: String): String =
    if (hex.length <= 16) hex else hex.take(8) + "…" + hex.takeLast(6)
