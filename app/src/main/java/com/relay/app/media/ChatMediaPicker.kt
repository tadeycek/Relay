package com.relay.app.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

enum class ChatMediaSource { PHOTO, VIDEO, CAMERA, GIF }
data class PickedChatMedia(val uri: Uri, val mime: String, val selectionId: String = "")
class ChatMediaPicker(
    val draftFor: (Long) -> List<PickedChatMedia>,
    val open: (Long, ChatMediaSource) -> Unit,
    val remove: (Long, Set<String>) -> Unit,
)
val LocalChatMediaPicker = staticCompositionLocalOf<ChatMediaPicker> { error("Missing activity media picker") }

/** Keep launchers registered at the Activity root while Calculator disposes the private screens. */
@Composable
fun rememberChatMediaPicker(onLaunch: () -> Unit = {}, onReturn: () -> Unit = {}): ChatMediaPicker {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf(-1L) }
    var drafts by rememberSaveable(stateSaver = listSaver<Map<Long, List<PickedChatMedia>>, String>(
        save = { chats -> chats.flatMap { (id, items) -> items.flatMap { listOf(id.toString(), it.uri.toString(), it.mime, it.selectionId) } } },
        restore = { values -> values.chunked(4).groupBy { it[0].toLong() }.mapValues { (_, items) ->
            items.map { PickedChatMedia(Uri.parse(it[1]), it[2], it[3]) }
        } },
    )) { mutableStateOf(emptyMap()) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun selected(uri: Uri, fallback: String) {
        val selections = drafts[target].orEmpty()
        if (selections.none { it.uri == uri }) drafts = drafts + (target to (selections + PickedChatMedia(uri,
            runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: fallback,
            java.util.UUID.randomUUID().toString())))
    }
    fun documents(uris: List<Uri>, fallback: String) {
        for (uri in uris) {
            // Document grants survive Activity recreation and disposal of the private navigation.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selected(uri, fallback)
        }
    }
    fun release(items: List<PickedChatMedia>) {
        val retained = drafts.values.flatten().map { it.uri }.toSet()
        items.map { it.uri }.distinct().filterNot { it in retained }.forEach { uri -> runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        try { documents(uris, "image/jpeg") } finally { onReturn() }
    }
    val gif = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try { if (uri != null) selected(uri, "image/gif") } finally { onReturn() }
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        try { documents(uris, "video/mp4") } finally { onReturn() }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        try { if (success) cameraUri?.let { selected(Uri.parse(it), "image/jpeg") } } finally { onReturn() }
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri.toString()
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try { takePhoto() } catch (e: Exception) { onReturn(); android.widget.Toast.makeText(context, "Couldn’t open the media picker", android.widget.Toast.LENGTH_LONG).show() }
        } else onReturn()
    }
    return ChatMediaPicker(
        draftFor = { contact -> drafts[contact].orEmpty() },
        open = { contact, source ->
            target = contact
            onLaunch()
            try { when (source) {
                ChatMediaSource.GIF -> gif.launch("image/gif")
                ChatMediaSource.PHOTO -> photo.launch(arrayOf("image/*"))
                ChatMediaSource.VIDEO -> video.launch(arrayOf("video/*"))
                ChatMediaSource.CAMERA -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    takePhoto() else permission.launch(Manifest.permission.CAMERA)
            } } catch (e: Exception) { onReturn(); android.widget.Toast.makeText(context, "Couldn’t open the media picker", android.widget.Toast.LENGTH_LONG).show() }
        },
        remove = { contact, ids ->
            val selected = drafts[contact].orEmpty()
            val remaining = selected.filterNot { it.selectionId in ids }
            drafts = if (remaining.isEmpty()) drafts - contact else drafts + (contact to remaining)
            release(selected.filter { it.selectionId in ids })
        },
    )
}
