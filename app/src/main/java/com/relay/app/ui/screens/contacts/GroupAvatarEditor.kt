package com.relay.app.ui.screens.contacts

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.relay.app.groups.GroupAvatars
import com.relay.app.media.LocalPickerSession
import com.relay.app.mms.MediaCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GroupAvatarEditor(id: Long, canManage: Boolean) {
    val context = LocalContext.current
    val session = LocalPickerSession.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> session.returned(); if (uri != null) draft = uri.toString() }
    if (canManage) {
        TextButton(enabled = !busy, onClick = { session.launch(); picker.launch("image/*") }) {
            Icon(Icons.Outlined.AddPhotoAlternate, "Choose or replace group picture"); Text("Group picture")
        }
        draft?.let { uri ->
            AsyncImage(Uri.parse(uri), "New group picture preview", Modifier.size(120.dp))
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val photo = MediaCompressor.compressImage(context, Uri.parse(uri), 8192, 256)
                        check(photo is MediaCompressor.Result.Success) { "Couldn't prepare that image. Choose another photo." }
                        try { GroupAvatars.set(context, id, photo.file.readBytes()) } finally { photo.file.delete() }
                    } }
                    busy = false
                    result.onSuccess { draft = null; error = null }.onFailure { error = it.message }
                }
            }) { Text(if (busy) "Saving…" else "Save group picture") }
            TextButton(enabled = !busy, onClick = { draft = null }) { Text("Cancel picture") }
        }
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { GroupAvatars.set(context, id, null) } }
                busy = false; result.onSuccess { draft = null }.onFailure { error = it.message }
            }
        }) { Text("Remove group picture") }
    } else Text("Only the creator can change the group picture")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
