package com.relay.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.LocationOn
import com.relay.app.util.GeoLinks
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.relay.app.crypto.RelayFileCrypto
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun MessageBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isSent) Arrangement.End else Arrangement.Start,
    ) {
        when (message.type) {
            MessageType.TEXT -> TextBubble(message)
            MessageType.LOCATION -> LocationBubble(message)
            MessageType.IMAGE -> ImageBubble(message)
            MessageType.VIDEO -> VideoBubble(message)
            MessageType.LOCATION_REQUEST -> LocationRequestBubble(message)
            MessageType.LOCATION_DECLINED -> LocationDeclinedBubble(message)
        }
    }
}

@Composable
private fun TextBubble(message: Message) {
    val bgColor = if (message.isSent) Accent else Surface2
    val textColor = if (message.isSent) OnAccent else TextPrimary
    val shape = bubbleShape(message.isSent)

    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = message.body,
            color = textColor,
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Row(
            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!message.isSent && !message.senderVerified) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Decrypted, but the sender couldn't be verified",
                    tint = TextSecondary,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                color = if (message.isSent) OnAccent.copy(alpha = 0.6f) else TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
            )
            if (message.isSent) {
                val isRead = message.readAt != null
                val (icon, description) = when {
                    isRead -> Icons.Filled.DoneAll to "Read"
                    message.deliveryState == DeliveryState.QUEUED -> Icons.Outlined.Schedule to "Waiting to send"
                    message.deliveryState == DeliveryState.FAILED -> Icons.Outlined.ErrorOutline to "Failed to send"
                    else -> Icons.Filled.Done to "Sent"
                }
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = if (isRead) OnAccent else OnAccent.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationBubble(message: Message) {
    val lat = message.lat ?: return
    val lng = message.lng ?: return
    val shape = bubbleShape(message.isSent)

    val context = LocalContext.current

    // No map tile here: Relay has no map of its own (fetching tiles would reveal the viewer's IP to a
    // map server). "Open in Maps" hands the point to the user's own maps app, on a deliberate tap.
    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(Surface2)
            .clickable { openInMaps(context, lat, lng, message.pinLabel) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = if (message.isSent) "You shared a location" else "Shared a location",
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 13.sp,
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = "${"%.5f".format(lat)}, ${"%.5f".format(lng)}",
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 11.sp,
            )
            Text(
                text = "Tap to open in Maps",
                color = Accent,
                fontFamily = IbmPlexSans,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!message.pinLabel.isNullOrBlank()) {
                Text(
                    text = message.pinLabel,
                    color = TextPrimary,
                    fontFamily = IbmPlexSans,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun openInMaps(context: android.content.Context, lat: Double, lng: Double, label: String?) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, GeoLinks.toUri(lat, lng, label))
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No maps app installed", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Received media is cached at rest as ciphertext ([RelayFileCrypto]); this produces a short-lived
 * plaintext copy for Coil/MediaMetadataRetriever to render. Files that were never encrypted (e.g.
 * media we sent) come back through unchanged via [RelayFileCrypto.decryptedViewCopy]'s fallback.
 */
@Composable
private fun rememberDisplayFile(path: String): File? {
    val context = LocalContext.current
    return produceState<File?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val original = File(path)
                if (!original.exists()) null else RelayFileCrypto.decryptedViewCopy(context, original)
            }.getOrNull()
        }
    }.value
}

@Composable
private fun ImageBubble(message: Message) {
    val uri = message.mediaUri ?: return
    val shape = bubbleShape(message.isSent)
    val displayFile = if (uri.startsWith("/")) rememberDisplayFile(uri) else null
    val model: Any? = if (uri.startsWith("/")) displayFile else android.net.Uri.parse(uri)

    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(Surface2),
    ) {
        AsyncImage(
            model = model,
            contentDescription = "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )
        if (message.body.isNotEmpty()) {
            Text(
                text = message.body,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            color = TextSecondary,
            fontFamily = IbmPlexMono,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun VideoBubble(message: Message) {
    val path = message.mediaUri ?: return
    val shape = bubbleShape(message.isSent)
    val displayFile = rememberDisplayFile(path)

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = displayFile) {
        val target = displayFile
        value = if (target == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(target.absolutePath)
                    retriever.getFrameAtTime(0)
                } catch (e: Exception) {
                    null
                } finally {
                    retriever.release()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(Surface2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bm = thumbnail
            if (bm != null) {
                androidx.compose.foundation.Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = "Video thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        if (message.body.isNotEmpty()) {
            Text(
                text = message.body,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            color = TextSecondary,
            fontFamily = IbmPlexMono,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LocationRequestBubble(message: Message) {
    val shape = bubbleShape(message.isSent)
    val label = if (message.isSent) "Location request sent" else "Location request received"

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(if (message.isSent) Accent.copy(alpha = 0.15f) else Surface2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.LocationSearching,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(16.dp),
        )
        Column {
            Text(text = label, color = TextSecondary, fontFamily = IbmPlexSans, fontSize = 13.sp)
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LocationDeclinedBubble(message: Message) {
    val shape = bubbleShape(message.isSent)
    val label = if (message.isSent) "You declined location request" else "Location request declined"

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.LocationOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Column {
            Text(text = label, color = TextSecondary, fontFamily = IbmPlexSans, fontSize = 13.sp)
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

private fun bubbleShape(isSent: Boolean): androidx.compose.ui.graphics.Shape = RectangleShape
