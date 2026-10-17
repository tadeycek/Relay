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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

enum class ChatMediaSource { PHOTO, VIDEO, CAMERA, GIF }
data class PickedChatMedia(val uri: Uri, val mime: String, val selectionId: String = "")
class ChatMediaPicker(
    val draftFor: (Long) -> PickedChatMedia?,
    val open: (Long, ChatMediaSource) -> Unit,
    val clear: (Long) -> Unit,
)
val LocalChatMediaPicker = staticCompositionLocalOf<ChatMediaPicker> { error("Missing activity media picker") }

/** Keep launchers registered at the Activity root while Calculator disposes the private screens. */
@Composable
fun rememberChatMediaPicker(onLaunch: () -> Unit = {}, onReturn: () -> Unit = {}): ChatMediaPicker {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf(-1L) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionId by rememberSaveable { mutableStateOf("") }
    var selectedMime by rememberSaveable { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun selected(uri: Uri, fallback: String) {
        selectionId = java.util.UUID.randomUUID().toString()
        selectedUri = uri.toString()
        selectedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: fallback
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try { if (uri != null) selected(uri, "image/jpeg") } finally { onReturn() }
    }
    val gif = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try { if (uri != null) selected(uri, "image/gif") } finally { onReturn() }
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try { if (uri != null) selected(uri, "video/mp4") } finally { onReturn() }
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
        draftFor = { contact ->
            if (contact == target) selectedUri?.let { PickedChatMedia(Uri.parse(it), selectedMime, selectionId) } else null
        },
        open = { contact, source ->
            if (target != contact) { selectedUri = null; selectedMime = "" }
            target = contact
            onLaunch()
            try { when (source) {
                ChatMediaSource.GIF -> gif.launch("image/gif")
                ChatMediaSource.PHOTO -> photo.launch("image/*")
                ChatMediaSource.VIDEO -> video.launch("video/*")
                ChatMediaSource.CAMERA -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    takePhoto() else permission.launch(Manifest.permission.CAMERA)
            } } catch (e: Exception) { onReturn(); android.widget.Toast.makeText(context, "Couldn’t open the media picker", android.widget.Toast.LENGTH_LONG).show() }
        },
        clear = { contact -> if (target == contact) { selectedUri = null; selectedMime = "" } },
    )
}
