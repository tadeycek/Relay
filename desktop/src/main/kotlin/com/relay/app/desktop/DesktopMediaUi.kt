package com.relay.app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** What a media bubble shows for a journal row. Pure so the state machine is testable without a display. */
internal object MediaBubbleModel {
    fun statusLine(r: DesktopMediaRecord): String? = when (r.state) {
        "QUEUED" -> if (r.localSource) "Waiting to upload" else "Waiting to download"
        "TRANSFERRING" -> if (r.localSource) "Uploading…" else "Downloading…"
        "STORED" -> "Saved; confirming with the server…"
        "CONFIRM_PENDING" -> "Saved. Server confirmation pending"
        "FAILED" -> r.error ?: "Transfer failed"
        "UNSUPPORTED" -> r.error ?: "Unsupported media"
        "CANCELLED" -> "Cancelled"
        "SEND_DISABLED" -> r.error ?: "Sending is disabled"
        else -> null
    }
    fun inFlight(r: DesktopMediaRecord) = r.state in setOf("QUEUED", "TRANSFERRING", "STORED")
    fun canRetry(r: DesktopMediaRecord) = (r.state in setOf("FAILED", "CONFIRM_PENDING", "QUEUED")) && !r.permanent
    fun hasBytes(r: DesktopMediaRecord) = r.state in setOf("STORED", "CONFIRM_PENDING", "COMPLETE") || (r.localSource && r.state !in setOf("CANCELLED", "DELETED", "UNSUPPORTED"))
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
    /** Extension-independent routing of a picked/dropped file: known media magic goes to the media path. */
    fun isMediaFile(path: Path): Boolean = runCatching {
        java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { MediaSafety.sniff(it.readNBytes(32)) != null }
    }.getOrDefault(false)
}

/**
 * Photo/video bubble. Only a verified, bounded still image is decoded (by Skia, first frame only, so a
 * GIF never animates); video is a placeholder with explicit open/save; everything else is a plain
 * attachment. Nothing here fetches, parses a video, or opens a file without a click.
 */
@Composable
internal fun MediaBubble(engine: DesktopEngine, id: String, outgoing: Boolean, caption: String?) {
    val scope = rememberCoroutineScope()
    var record by remember(id) { mutableStateOf<DesktopMediaRecord?>(null) }
    var progress by remember(id) { mutableStateOf<Pair<Long, Long>?>(null) }
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var imageLoaded by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf<String?>(null) }
    var confirmOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(engine, id) {
        while (true) {
            withContext(Dispatchers.IO) { record = engine.store.mediaRecord(id); progress = engine.media?.progress(id) }
            delay(300)
        }
    }
    val r = record
    LaunchedEffect(id, r?.verdict, r?.state) {
        if (r != null && !imageLoaded && r.verdict == "IMAGE" && MediaBubbleModel.hasBytes(r)) {
            val bytes = withContext(Dispatchers.IO) { engine.media?.previewBytes(id) }
            image = bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
            imageLoaded = true
            if (bytes == null) message = "This image failed its safety check and was not previewed."
        }
    }
    val fg = if (outgoing) Color.White else Color(0xFFDBDEE1)
    val accent = if (outgoing) Color.White else Color(0xFF8AB4F8)
    Column(Modifier.padding(8.dp).testTag("media-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (r == null) { Text("Loading media…", color = fg, fontSize = 12.sp); return@Column }
        val img = image.takeIf { r.state !in setOf("DELETED", "CANCELLED") }
        when {
            img != null -> Image(img, contentDescription = "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 340.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(8.dp)).testTag("media-image-$id"))
            r.verdict == "VIDEO" -> Box(Modifier.size(width = 280.dp, height = 120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)).testTag("media-video-$id")) {
                Column(Modifier.align(androidx.compose.ui.Alignment.Center), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("▶", color = fg, fontSize = 30.sp)
                    Text("Video · MP4 · not previewed", color = fg, fontSize = 11.sp)
                }
            }
            r.verdict == "ATTACHMENT" -> Text("📎 Attachment — ${r.note ?: "not previewed"}", color = fg, fontSize = 12.sp, modifier = Modifier.testTag("media-attachment-$id"))
            MediaBubbleModel.inFlight(r) || r.verdict == "UNKNOWN" -> Box(Modifier.size(width = 240.dp, height = 90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000))) {
                Text(if (r.mime.startsWith("video")) "Video" else "Photo", color = fg, fontSize = 28.sp, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        }
        caption?.takeIf { it.isNotBlank() }?.let { Text(it, color = fg, fontSize = 13.sp) }
        MediaBubbleModel.statusLine(r)?.let { Text(it, color = fg.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.testTag("media-status-$id")) }
        progress?.let { (done, total) ->
            if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, color = accent, trackColor = accent.copy(alpha = 0.25f), modifier = Modifier.widthIn(max = 280.dp))
        }
        message?.let { Text(it, color = fg, fontSize = 11.sp) }
        Row {
            if (MediaBubbleModel.canRetry(r)) TextButton(onClick = { scope.launch { engine.retryMedia(id) } }) { Text("Retry", color = accent, fontSize = 11.sp) }
            if (MediaBubbleModel.inFlight(r) || r.state == "FAILED" && !r.permanent) TextButton(onClick = { engine.media?.cancel(id) }) { Text("Cancel", color = accent, fontSize = 11.sp) }
            if (MediaBubbleModel.hasBytes(r) && r.verdict in setOf("IMAGE", "VIDEO", "ATTACHMENT")) {
                TextButton(onClick = {
                    val target = desktopFileDialog(true, "relay-media." + (r.mime.substringAfter('/', "bin").replace("jpeg", "jpg")))
                    if (target != null) scope.launch {
                        message = try { withContext(Dispatchers.IO) { engine.media?.saveAs(id, target) }; "Saved ${target.fileName}" }
                        catch (e: Exception) { "Save failed: pick a new file name in a normal folder" }
                    }
                }) { Text("Save as…", color = accent, fontSize = 11.sp) }
            }
            if (MediaBubbleModel.hasBytes(r) && r.verdict == "VIDEO") TextButton(onClick = { confirmOpen = true }, modifier = Modifier.testTag("media-open-$id")) { Text("Open externally…", color = accent, fontSize = 11.sp) }
            TextButton(onClick = { engine.media?.cancel(id, delete = true) }) { Text("Delete", color = accent, fontSize = 11.sp) }
        }
    }
    if (confirmOpen) AlertDialog(onDismissRequest = { confirmOpen = false }, title = { Text("Open video in another app?") },
        text = { Text("Relay does not play videos itself. A verified private copy will be handed to your system video player; it is erased when you delete this message or after 7 days.") },
        confirmButton = { TextButton(onClick = {
            confirmOpen = false
            scope.launch {
                message = try { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(engine.media!!.exportForOpen(id).toFile()) }; "Opened in your video player" }
                catch (e: Exception) { "No application available to open this video" }
            }
        }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancel") } })
}

/** Native photo/video picker. A filter narrows the dialog; the controller still validates by magic number. */
internal fun desktopMediaDialog(): Path? {
    val dialog = FileDialog(null as Frame?, "Send photo or video", FileDialog.LOAD)
    try {
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "mp4", "m4v") }
        dialog.isVisible = true
        return dialog.file?.let { Path.of(dialog.directory, it) }
    } finally { dialog.dispose() }
}
