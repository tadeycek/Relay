package com.relay.app.ui.screens.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.relay.app.sms.LocationShareService
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.relay.app.ui.components.MessageBubble
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ChatScreen(contactId: Long, navController: NavController) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(contactId, app))
    val messages by vm.messages.collectAsState()
    val contact by vm.contact.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMimeType by remember { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var cameraFileUri by remember { mutableStateOf<Uri?>(null) }

    val mediaPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }
    val permState = rememberMultiplePermissionsState(mediaPermissions)

    // Share-my-location: ask for location permission the first time, then share straight away.
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION) { granted ->
        if (granted) contact?.let { LocationShareService.share(context, it) }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMimeType = context.contentResolver.getType(uri) ?: "video/mp4"
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraFileUri?.let {
                pendingMediaUri = it
                pendingMimeType = "image/jpeg"
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.toastMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        vm.registerSmsUpdates(context)
        onDispose { vm.unregisterSmsUpdates(context) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val lastReceivedTs = messages.filter { !it.isSent }.maxOfOrNull { it.timestamp } ?: 0L
    LaunchedEffect(lastReceivedTs) {
        if (lastReceivedTs > 0L) vm.sendReadReceipt(context, lastReceivedTs)
    }

    if (showSourceDialog) {
        MediaSourceDialog(
            onDismiss = { showSourceDialog = false },
            onPhoto = {
                showSourceDialog = false
                if (permState.allPermissionsGranted) {
                    photoPickerLauncher.launch("image/*")
                } else {
                    permState.launchMultiplePermissionRequest()
                }
            },
            onVideo = {
                showSourceDialog = false
                if (permState.allPermissionsGranted) {
                    videoPickerLauncher.launch("video/*")
                } else {
                    permState.launchMultiplePermissionRequest()
                }
            },
            onCamera = {
                showSourceDialog = false
                if (permState.allPermissionsGranted) {
                    val file = createCameraFile(context)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cameraFileUri = uri
                    cameraLauncher.launch(uri)
                } else {
                    permState.launchMultiplePermissionRequest()
                }
            },
        )
    }

    Scaffold(
        topBar = {
            RelayTopBar(
                title = contact?.name ?: "",
                onBack = { navController.popBackStack() },
                titleIcon = if (contact?.publicKey != null) {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Encrypted",
                            tint = Accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
                actions = {
                    IconButton(onClick = {
                        val c = contact
                        if (c != null) {
                            if (locationPermission.status.isGranted) {
                                LocationShareService.share(context, c)
                            } else {
                                locationPermission.launchPermissionRequest()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.MyLocation,
                            contentDescription = "Share my location",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { vm.sendLocationRequest(context) }) {
                        Icon(
                            imageVector = Icons.Outlined.LocationSearching,
                            contentDescription = "Request location",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
        containerColor = Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            MessageInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                pendingMediaUri = pendingMediaUri,
                onClearMedia = { pendingMediaUri = null; pendingMimeType = "" },
                onAttach = { showSourceDialog = true },
                onSend = {
                    val uri = pendingMediaUri
                    if (uri != null) {
                        vm.sendMedia(uri, pendingMimeType, inputText.trim(), context)
                        pendingMediaUri = null
                        pendingMimeType = ""
                        inputText = ""
                    } else if (inputText.isNotBlank()) {
                        vm.sendText(inputText.trim(), context)
                        inputText = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun MediaSourceDialog(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onCamera: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach media", fontFamily = IbmPlexSans) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Photo from gallery", fontFamily = IbmPlexSans)
                }
                TextButton(onClick = onVideo, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.VideoLibrary, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Video from gallery", fontFamily = IbmPlexSans)
                }
                TextButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Take photo", fontFamily = IbmPlexSans)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = IbmPlexSans)
            }
        },
    )
}

@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    pendingMediaUri: Uri?,
    onClearMedia: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = pendingMediaUri != null || value.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pendingMediaUri != null) {
            Box(modifier = Modifier.size(72.dp)) {
                AsyncImage(
                    model = pendingMediaUri,
                    contentDescription = "Attachment preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RectangleShape),
                )
                IconButton(
                    onClick = onClearMedia,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.6f), RectangleShape),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove attachment",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach",
                    tint = if (pendingMediaUri != null) Accent else TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message", color = TextSecondary, fontFamily = IbmPlexSans) },
                singleLine = false,
                maxLines = 4,
                shape = RectangleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedContainerColor = Surface2,
                    unfocusedContainerColor = Surface2,
                ),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (canSend) Accent else Surface2,
                        shape = RectangleShape,
                    ),
            ) {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (canSend) OnAccent else TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun createCameraFile(context: Context): File {
    val dir = File(context.cacheDir, "mms").also { it.mkdirs() }
    return File(dir, "camera_${System.currentTimeMillis()}.jpg")
}
