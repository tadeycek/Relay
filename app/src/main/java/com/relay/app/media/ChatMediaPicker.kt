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

enum class ChatMediaSource { PHOTO, VIDEO, CAMERA }
data class PickedChatMedia(val uri: Uri, val mime: String)
class ChatMediaPicker(
    val draftFor: (Long) -> PickedChatMedia?,
    val open: (Long, ChatMediaSource) -> Unit,
    val clear: (Long) -> Unit,
)
val LocalChatMediaPicker = staticCompositionLocalOf<ChatMediaPicker> { error("Missing activity media picker") }

/** Keep launchers registered at the Activity root while Calculator disposes the private screens. */
@Composable
fun rememberChatMediaPicker(): ChatMediaPicker {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf(-1L) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMime by rememberSaveable { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun selected(uri: Uri, fallback: String) {
        selectedUri = uri.toString()
        selectedMime = context.contentResolver.getType(uri) ?: fallback
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selected(uri, "image/jpeg")
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selected(uri, "video/mp4")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { selected(Uri.parse(it), "image/jpeg") }
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri.toString()
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto()
    }
    return ChatMediaPicker(
        draftFor = { contact ->
            if (contact == target) selectedUri?.let { PickedChatMedia(Uri.parse(it), selectedMime) } else null
        },
        open = { contact, source ->
            if (target != contact) { selectedUri = null; selectedMime = "" }
            target = contact
            when (source) {
                ChatMediaSource.PHOTO -> photo.launch("image/*")
                ChatMediaSource.VIDEO -> video.launch("video/*")
                ChatMediaSource.CAMERA -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    takePhoto() else permission.launch(Manifest.permission.CAMERA)
            }
        },
        clear = { contact -> if (target == contact) { selectedUri = null; selectedMime = "" } },
    )
}
