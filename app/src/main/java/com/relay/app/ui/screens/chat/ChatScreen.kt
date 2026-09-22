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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.relay.app.sms.LocationShareService
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.RelayBottomSheet
import com.relay.app.ui.components.RelayTextField
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelaySpacing
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
    val unreadAnchor by vm.unreadAnchorId.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val items = remember(messages, unreadAnchor) {
        ChatTimeline.build(messages, unreadAnchor, System.currentTimeMillis())
    }

    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMimeType by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
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

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMimeType = context.contentResolver.getType(uri) ?: "video/mp4"
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraFileUri?.let {
                pendingMediaUri = it
                pendingMimeType = "image/jpeg"
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.toastMessage.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    DisposableEffect(Unit) {
        vm.registerSmsUpdates(context)
        onDispose { vm.unregisterSmsUpdates(context) }
    }

    // First open: jump to where the new messages begin (or the bottom); afterwards follow new messages.
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            val divider = items.indexOfFirst { it is ChatItem.UnreadDivider }
            listState.scrollToItem(if (divider >= 0) divider else items.lastIndex)
            initialScrollDone = true
        } else {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    val lastReceivedTs = messages.filter { !it.isSent }.maxOfOrNull { it.timestamp } ?: 0L
    LaunchedEffect(lastReceivedTs) {
        if (lastReceivedTs > 0L) vm.sendReadReceipt(context, lastReceivedTs)
    }

    if (showAttachSheet) {
        AttachSheet(
            onDismiss = { showAttachSheet = false },
            onPhoto = {
                showAttachSheet = false
                if (permState.allPermissionsGranted) photoPickerLauncher.launch("image/*") else permState.launchMultiplePermissionRequest()
            },
            onVideo = {
                showAttachSheet = false
                if (permState.allPermissionsGranted) videoPickerLauncher.launch("video/*") else permState.launchMultiplePermissionRequest()
            },
            onCamera = {
                showAttachSheet = false
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
        // The top bar and composer already handle the status/navigation-bar insets themselves
        // (statusBarsPadding / navigationBarsPadding), so the Scaffold must not reserve that space a
        // second time — on a phone with a tall 3-button navigation bar that doubling showed up as a
        // large empty gap above the composer.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                contact = contact,
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = {
                        val c = contact
                        if (c != null) {
                            if (locationPermission.status.isGranted) LocationShareService.share(context, c)
                            else locationPermission.launchPermissionRequest()
                        }
                    }) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = "Share my location", tint = TextSecondary)
                    }
                    IconButton(onClick = { vm.sendLocationRequest(context) }) {
                        Icon(Icons.Outlined.LocationSearching, contentDescription = "Ask for their location", tint = TextSecondary)
                    }
                },
            )
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (items.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    title = "Say hello",
                    message = "Messages in this chat are end-to-end encrypted.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md),
                ) {
                    items(items, key = { it.key }) { item -> ChatItemRow(item, contactName = contact?.name ?: "") }
                }
            }

            Composer(
                value = inputText,
                onValueChange = { inputText = it },
                pendingMediaUri = pendingMediaUri,
                onClearMedia = { pendingMediaUri = null; pendingMimeType = "" },
                onAttach = { showAttachSheet = true },
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
private fun AttachSheet(onDismiss: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit, onCamera: () -> Unit) {
    RelayBottomSheet(onDismiss = onDismiss) {
        ListRow(headline = "Photo from gallery", onClick = onPhoto, leading = { AttachIcon(Icons.Outlined.Image) })
        ListRow(headline = "Video from gallery", onClick = onVideo, leading = { AttachIcon(Icons.Outlined.VideoLibrary) })
        ListRow(headline = "Take a photo", onClick = onCamera, leading = { AttachIcon(Icons.Outlined.CameraAlt) })
    }
}

@Composable
private fun AttachIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, tint = TextSecondary)
}

/** Attach, a pill-shaped text field and a send button; shows a preview of a pending photo above. */
@Composable
internal fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    pendingMediaUri: Uri? = null,
    onClearMedia: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    placeholder: String = "Message",
) {
    val canSend = pendingMediaUri != null || value.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .navigationBarsPadding()
            .padding(horizontal = RelaySpacing.md, vertical = RelaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
    ) {
        if (pendingMediaUri != null) {
            Box(modifier = Modifier.size(72.dp)) {
                AsyncImage(
                    model = pendingMediaUri,
                    contentDescription = "Attachment preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                )
                IconButton(
                    onClick = onClearMedia,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.TopEnd)
                        // A dark scrim over a photo, whatever the theme.
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove attachment", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
        ) {
            if (onAttach != null) IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach a photo or video",
                    tint = if (pendingMediaUri != null) Accent else TextSecondary,
                )
            }
            RelayTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Accent else Surface2),
            ) {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (canSend) OnAccent else TextSecondary,
                        modifier = Modifier.size(22.dp),
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
