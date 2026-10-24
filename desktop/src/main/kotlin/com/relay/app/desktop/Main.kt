package com.relay.app.desktop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.relay.app.transport.SendResult
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Minimal but real desktop shell: conversation list, message thread and a composer. It is deliberately
 * small; WP06's product scope is text messaging, not the final Discord-shaped visual design.
 *
 * Phone linking is a stub: this install creates a local Nostr identity. The "Local only" label and
 * the peer-key field make that visible instead of pretending a phone is paired.
 */
fun main(args: Array<String>) {
    val options = Args.parse(args)
    val paths = DesktopPaths(options.configDir?.let(Path::of), options.dataDir?.let(Path::of))
    val engine = DesktopEngine.create(paths, options.mailboxUrl, options.peerInnerKeys)
    if (options.printIdentity) {
        println("pubkey=${engine.identity.publicKeyHex()}")
        println("inner=${engine.keyMaterial.publicKeyBase64()}")
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

    if (options.mailboxUrl.isNotBlank()) engine.start()

    application {
        Window(
            onCloseRequest = {
                engine.close()
                exitApplication()
            },
            title = "Relay",
        ) {
            RelayTheme {
                App(engine, options.initialPeer)
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
                        println("usage: relay-desktop [--mailbox-url URL] [--peer HEX] [--peer-inner-key BASE64 PEER] [--config-dir DIR] [--data-dir DIR]")
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

@Composable
private fun App(engine: DesktopEngine, initialPeer: String?) {
    var conversations by remember { mutableStateOf(engine.conversations()) }
    var selectedPeer by remember { mutableStateOf(initialPeer) }
    var messages by remember { mutableStateOf(if (initialPeer != null) engine.messages(initialPeer) else emptyList()) }
    var newPeer by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        conversations = engine.conversations()
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
            Text(
                "Local-only desktop identity. Phone linking is WP05 part B.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                "Messages: ${messages.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    enabled = draft.isNotBlank() && selectedPeer != null && engine.status.value != com.relay.app.transport.TransportStatus.STOPPED,
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
