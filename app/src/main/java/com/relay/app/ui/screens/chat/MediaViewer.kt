package com.relay.app.ui.screens.chat

import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.relay.app.crypto.RelayFileCrypto
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Private, per-view plaintext copies are removed when their bubble leaves composition. */
@Composable
internal fun rememberDisplayFile(path: String): File? {
    val context = LocalContext.current
    val generation by com.relay.app.messaging.MessageDeletion.viewGeneration.collectAsState()
    return produceState<File?>(null, path, generation) {
        var temporary: File? = null
        try {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(path)
                    val original = when {
                        path.startsWith("/") -> File(path)
                        uri.scheme == "file" -> File(requireNotNull(uri.path))
                        else -> null
                    }
                    val dir = File(context.cacheDir, "private-media-view").apply { mkdirs() }
                    val file = File.createTempFile("view-", ".media", dir).also { temporary = it }
                    if (original != null) {
                        require(original.isFile)
                        val bytes = runCatching { RelayFileCrypto.readDecrypted(context, original) }
                            .getOrElse { original.readBytes() }
                        file.writeBytes(bytes)
                    } else {
                        require(uri.scheme == "content")
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }
                    file
                }.getOrNull()
            }
            awaitCancellation()
        } finally {
            temporary?.delete()
        }
    }.value
}

/** Private viewer; only the explicit, confirmed Save action exports a plaintext photo. */
@Composable
internal fun MediaViewer(file: File, isVideo: Boolean, onDismiss: () -> Unit, message: com.relay.app.data.model.Message? = null, group: Long? = null) {
    val context = LocalContext.current
    val pickerSession = com.relay.app.media.LocalPickerSession.current
    val extension = remember(file) { runCatching { photoExtension(file) }.getOrDefault("img") }
    val mime = when(extension) { "jpg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/*" }
    val scope = rememberCoroutineScope()
    var saving by remember(file) { mutableStateOf(false) }
    var confirmSave by remember(file) { mutableStateOf(false) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mime)) { uri ->
        pickerSession.returned()
        if(uri != null && message != null) {
            saving = true
            scope.launch {
                var copied = false
                val result = withContext(Dispatchers.IO + NonCancellable) { runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"w")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                    copied = true
                    com.relay.app.messaging.ConversationEvents.saved(context,message,group)
                } }
                saving = false
                android.widget.Toast.makeText(context,if(result.isSuccess) "Photo saved; notice queued" else if(copied) "Photo saved, but notice could not be queued: ${result.exceptionOrNull()?.message}" else "Photo could not be saved: ${result.exceptionOrNull()?.message}",android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    if(confirmSave) AlertDialog(onDismissRequest = { confirmSave=false },
        title = { Text("Save this photo?") },
        text = { Text("A copy will be saved outside the app. An in-chat notice identifies you and this exact photo.") },
        confirmButton = { TextButton(onClick = { confirmSave=false; pickerSession.launch(); save.launch("Relay-photo-${message?.msgId}.$extension") }) { Text("Choose destination") } },
        dismissButton = { TextButton(onClick = { confirmSave=false }) { Text("Cancel") } })
    var error by remember(file) { mutableStateOf(false) }
    var playingVideo by remember(file) { mutableStateOf<VideoView?>(null) }
    var videoControls by remember(file) { mutableStateOf<MediaController?>(null) }
    DisposableEffect(file) {
        onDispose {
            // The native controller owns a popup window; stopping its player alone leaves it open.
            videoControls?.hide()
            playingVideo?.setOnPreparedListener(null)
            playingVideo?.setMediaController(null)
            playingVideo?.stopPlayback()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            if (error) {
                Text("This media couldn't be opened. The file may be missing or its format unsupported.",
                    color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            } else if (isVideo) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    FrameLayout(context).apply {
                        contentDescription = "Video player"
                        val video = VideoView(context)
                        addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                        val controls = MediaController(context).apply { setAnchorView(video) }
                        videoControls = controls
                        video.setMediaController(controls)
                        video.setOnPreparedListener { video.start(); controls.show(3_000) }
                        video.setOnErrorListener { _, _, _ -> error = true; true }
                        video.setVideoURI(Uri.fromFile(file))
                        playingVideo = video
                    }
                })
            } else {
                var zoom by remember(file) { mutableFloatStateOf(1f) }
                var pan by remember(file) { mutableStateOf(Offset.Zero) }
                val transforms = rememberTransformableState { factor, offset, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 5f)
                    pan = if (zoom == 1f) Offset.Zero else pan + offset
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(file)
                        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                    contentDescription = "Full screen photo", contentScale = ContentScale.Fit,
                    onError = { error = true },
                    modifier = Modifier.fillMaxSize().transformable(transforms).graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                    },
                )
            }
            if(!isVideo && message?.msgId != null) TextButton(onClick = { confirmSave=true }, enabled = !saving,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha=.65f))) {
                Text(if(saving) "Saving…" else "Save photo",color=Color.White)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = .65f))) {
                Icon(Icons.Outlined.Close, contentDescription = "Close media", tint = Color.White)
            }
        }
    }
}

/** Preserve animated bytes and actual format on export; never transcode or stretch. */
internal fun photoExtension(file: File): String = file.inputStream().use { input ->
    val b=ByteArray(12); val n=input.read(b)
    when {
        n>=3 && b[0]==0xff.toByte() && b[1]==0xd8.toByte() -> "jpg"
        n>=6 && String(b,0,3,Charsets.US_ASCII)=="GIF" -> "gif"
        n>=8 && b[0]==0x89.toByte() && String(b,1,3,Charsets.US_ASCII)=="PNG" -> "png"
        n>=12 && String(b,8,4,Charsets.US_ASCII)=="WEBP" -> "webp"
        else -> "img"
    }
}
