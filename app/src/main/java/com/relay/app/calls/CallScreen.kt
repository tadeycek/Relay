package com.relay.app.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified
import kotlinx.coroutines.delay

/**
 * The four call screens: incoming (accept/decline), outgoing (cancel), in-call (mute, speaker, hang up,
 * duration) and the ended card. Which controls exist comes from the tested [CallUiModel], so a missing
 * button is a test failure rather than a layout surprise.
 */
@Composable
fun CallScreen(callId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val model by CallController.screen.collectAsState()
    val muted by CallController.muted.collectAsState()
    val speaker by CallController.speaker.collectAsState()
    var everShown by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) CallController.accept(context)
        else Toast.makeText(context, CallGuard.startMessage(CallStartBlock.MIC_DENIED), Toast.LENGTH_LONG).show()
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CallAudio.apply(context, speaker)
    }
    LaunchedEffect(model?.stage) {
        if (model?.stage == CallStage.ACTIVE && CallAudio.needsBluetoothPermission(context))
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    LaunchedEffect(model) {
        if (model != null) everShown = true
        // A decayed screen closes the activity; if one never appears (stale notification), don't hang.
        if (model == null && everShown) onClose()
    }
    LaunchedEffect(callId) {
        delay(4_000)
        if (model == null) onClose()
    }

    val screen = model
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(RelaySpacing.xl),
    ) {
        if (screen == null) {
            Text(
                "Call ended",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = RelaySpacing.xxl)) {
                Avatar(screen)
                Spacer(Modifier.height(RelaySpacing.lg))
                Text(
                    text = screen.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(RelaySpacing.xs))
                Text(
                    text = if (screen.stage == CallStage.ACTIVE && screen.durationMs != null) tickingSubtitle(screen) else screen.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                if (screen.stage == CallStage.INCOMING) {
                    Spacer(Modifier.height(RelaySpacing.lg))
                    Text(
                        "Voice call, end-to-end encrypted. The server sees who and when, never the audio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = RelaySpacing.xl),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    screen.canAccept -> {
                        RoundButton("Decline", Icons.Filled.CallEnd, Danger, Color.White) { CallController.decline(context) }
                        RoundButton("Answer", Icons.Filled.Call, Verified, Color.Black) {
                            if (micGranted) CallController.accept(context) else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    screen.canCancel -> RoundButton("Cancel", Icons.Filled.CallEnd, Danger, Color.White) {
                        CallController.hangUp(context)
                    }
                    screen.stage == CallStage.CONNECTING -> RoundButton("Hang up", Icons.Filled.CallEnd, Danger, Color.White) {
                        CallController.hangUp(context)
                    }
                    screen.stage == CallStage.ACTIVE -> {
                        RoundButton(
                            if (muted) "Unmute" else "Mute",
                            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            if (muted) Accent else Surface2,
                            if (muted) OnAccent else TextPrimary,
                        ) { CallController.setMuted(context, !muted) }
                        RoundButton(
                            "Speaker",
                            if (speaker) Icons.Filled.VolumeUp else Icons.Outlined.VolumeOff,
                            if (speaker) Accent else Surface2,
                            if (speaker) OnAccent else TextPrimary,
                        ) { CallController.toggleSpeaker(context) }
                        RoundButton("Hang up", Icons.Filled.CallEnd, Danger, Color.White) { CallController.hangUp(context) }
                    }
                    screen.stage == CallStage.ENDED -> RoundButton("Close", Icons.Filled.CallEnd, Surface2, TextPrimary) { onClose() }
                }
            }
            Spacer(Modifier.height(RelaySpacing.xl))
        }
    }
}

/** One tick per second while a call is active, so the duration really moves. */
@Composable
private fun tickingSubtitle(screen: CallUiModel): String {
    val start = remember(screen.callId, screen.stage) { System.currentTimeMillis() - (screen.durationMs ?: 0L) }
    val label by produceState(CallUiModelFactory.duration(System.currentTimeMillis() - start)) {
        while (true) {
            value = CallUiModelFactory.duration(System.currentTimeMillis() - start)
            delay(1_000)
        }
    }
    return if (screen.subtitle == "Reconnecting…") screen.subtitle else label
}

@Composable
private fun Avatar(screen: CallUiModel) {
    val context = LocalContext.current
    val contact: Contact? = remember(screen.peerKey) {
        runCatching { ContactRepository(MessagingDb.get(context)).findByNostrPubkeySync(screen.peerKey) }.getOrNull()
    }
    if (contact != null) {
        ContactGlyph(contact, size = 104.dp)
    } else {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun RoundButton(
    label: String,
    icon: ImageVector,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(background),
        ) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(RelaySpacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}
