package com.relay.app.desktop

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

internal object DesktopFileDrops { val paths = kotlinx.coroutines.channels.Channel<Path>(kotlinx.coroutines.channels.Channel.BUFFERED) }

/** Explicit user action only. Save-as does not open; opening requires another click and executable consent. */
@Composable
internal fun FileBubble(engine: DesktopEngine, message: DesktopMessage) {
    val scope = rememberCoroutineScope()
    val actionColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val actionColors = ButtonDefaults.textButtonColors(contentColor = actionColor)
    var record by remember(message.id) { mutableStateOf(engine.store.fileRecord(message.id)) }
    LaunchedEffect(engine, message.id) { while (true) { record = engine.store.fileRecord(message.id); kotlinx.coroutines.delay(250) } }
    val current = record
    val manifest = com.relay.app.files.FileManifest.fromAuthenticatedPayload(
        com.relay.app.transport.RelayPayload(message.id, message.ts, message.body)) { true }
    var warning by remember { mutableStateOf<Path?>(null) }
    var saved by remember { mutableStateOf<Path?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (manifest == null || current == null) { Text("Unsupported file attachment"); return }
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(manifest.body.name)
        Text("${manifest.body.size} bytes · ${current.parts.count { it }}/${current.parts.size} parts · ${current.state}")
        current.error?.let { Text(it) }
        LinearProgressIndicator(progress = { current.parts.count { it }.toFloat() / current.parts.size }, color = actionColor, trackColor = actionColor.copy(alpha = 0.25f))
        Row {
            if (current.state !in listOf("CANCELLED", "DELETED")) {
                TextButton(colors = actionColors, onClick = { scope.launch { engine.retryFile(message.id) } }) { Text("Retry") }
                TextButton(colors = actionColors, onClick = { engine.files?.cancel(message.id) }) { Text("Cancel") }
                TextButton(colors = actionColors, onClick = {
                    val target = desktopFileDialog(true, manifest.body.name)
                    if (target != null) scope.launch {
                        try { saved = withContext(Dispatchers.IO) { engine.files?.saveAs(message.id, target) }; error = null }
                        catch (e: Exception) { error = "Save failed: destination must be new and not a symlink; verify storage space." }
                    }
                }) { Text("Save as…") }
            }
            TextButton(colors = actionColors, onClick = { engine.files?.cancel(message.id, delete = true) }) { Text("Delete") }
        }
        saved?.let { path -> TextButton(colors = actionColors, onClick = {
            if (DesktopFiles.needsOpenWarning(path.fileName.toString())) warning = path
            else runCatching { engine.files?.verifySaved(message.id, path) ?: error("File transport unavailable"); java.awt.Desktop.getDesktop().open(path.toFile()) }.onFailure { error = "No application available to open this file" }
        }) { Text("Open saved file") } }
        error?.let { Text(it) }
    }
    warning?.let { path -> AlertDialog(onDismissRequest = { warning = null }, title = { Text("Open executable file?") },
        text = { Text("This file can execute code. Only open it if you trust the sender and its contents.") },
        confirmButton = { TextButton(colors = actionColors, onClick = { warning = null; runCatching { engine.files?.verifySaved(message.id, path) ?: error("File transport unavailable"); java.awt.Desktop.getDesktop().open(path.toFile()) }.onFailure { error = "Cannot open file" } }) { Text("Open anyway") } },
        dismissButton = { TextButton(colors = actionColors, onClick = { warning = null }) { Text("Cancel") } }) }
}
internal fun desktopFileDialog(save: Boolean, name: String? = null): Path? {
    val dialog = FileDialog(null as Frame?, if (save) "Save verified file" else "Attach file", if (save) FileDialog.SAVE else FileDialog.LOAD)
    try { dialog.file = name; dialog.isVisible = true; return dialog.file?.let { Path.of(dialog.directory, it) } }
    finally { dialog.dispose() }
}
internal fun installFileDrop(window: java.awt.Window) {
    java.awt.dnd.DropTarget(window, object : java.awt.dnd.DropTargetAdapter() {
        override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
            try {
                if (!event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
                event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                val files = event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>
                files?.filterIsInstance<java.io.File>()?.forEach { DesktopFileDrops.paths.trySend(it.toPath()) }
                event.dropComplete(true)
            } catch (e: Exception) { event.dropComplete(false) }
        }
    })
}
