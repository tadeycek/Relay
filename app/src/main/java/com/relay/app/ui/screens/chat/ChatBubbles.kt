package com.relay.app.ui.screens.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.ui.components.DeliveryTick
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelayDataStyle
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.GeoLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val MaxBubbleWidth = 300.dp
private val MaxMediaWidth = 260.dp

/** Scrim over a photo or video thumbnail; must stay dark whatever the app theme, so it is not a theme colour. */
private val MediaScrim = Color.Black.copy(alpha = 0.55f)

/** One row of the chat list. */
@Composable
fun ChatItemRow(item: ChatItem, contactName: String, senderName: ((Long) -> String?)? = null) {
    when (item) {
        is ChatItem.Day -> DaySeparator(item.label)
        is ChatItem.UnreadDivider -> UnreadDivider(item.count)
        is ChatItem.Note -> SystemNote(item.message, contactName)
        is ChatItem.Bubble -> BubbleRow(item, senderName?.takeIf { !item.message.isSent && item.startsGroup }?.invoke(item.message.contactId))
    }
}

@Composable
private fun DaySeparator(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RelaySpacing.xl, bottom = RelaySpacing.sm),
    )
}

@Composable
private fun UnreadDivider(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RelaySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RelaySpacing.md),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Border)
        Text(
            text = if (count == 1) "1 new message" else "$count new messages",
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Border)
    }
}

/** Location requests and their replies: a quiet centred line, not a bubble. */
@Composable
private fun SystemNote(message: Message, contactName: String) {
    val (icon, text) = when (message.type) {
        MessageType.LOCATION_REQUEST ->
            Icons.Outlined.LocationSearching to
                if (message.isSent) "You asked for their location" else "$contactName asked for your location"
        else ->
            Icons.Outlined.LocationOff to
                if (message.isSent) "You declined the location request" else "$contactName declined your location request"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RelaySpacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = RelaySpacing.sm),
        )
    }
}

@Composable
private fun BubbleRow(item: ChatItem.Bubble, sender: String? = null) {
    val message = item.message
    val shape = bubbleShape(message.isSent, item.startsGroup)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Tight inside a group, roomier between groups.
            .padding(top = if (item.startsGroup) 10.dp else 2.dp),
        horizontalAlignment = if (message.isSent) Alignment.End else Alignment.Start,
    ) {
        when (message.type) {
            MessageType.TEXT -> TextBubble(message, shape)
            MessageType.LOCATION -> LocationCard(message, shape)
            MessageType.IMAGE -> ImageBubble(message, shape)
            MessageType.VIDEO -> VideoBubble(message, shape)
            // Requests and replies are notes and never reach here.
            MessageType.LOCATION_REQUEST, MessageType.LOCATION_DECLINED -> Unit
        }
        if (item.endsGroup) MessageMeta(message)
    }
}

/**
 * Corner tightness shows which bubbles belong together: the corner on the sender's side is small next
 * to a neighbour and at the bottom (a soft tail); every other corner is round.
 */
internal fun bubbleShape(isSent: Boolean, startsGroup: Boolean): Shape {
    val round = 16.dp
    val tight = 4.dp
    val senderTop = if (startsGroup) round else tight
    return if (isSent) {
        RoundedCornerShape(topStart = round, topEnd = senderTop, bottomEnd = tight, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = senderTop, topEnd = round, bottomEnd = round, bottomStart = tight)
    }
}

/** Time (and delivery state) once per group, under its last bubble. */
@Composable
private fun MessageMeta(message: Message) {
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!message.isSent && !message.senderVerified) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = "Decrypted, but the sender couldn't be verified",
                tint = TextSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        if (message.isSent) DeliveryTick(readAt = message.readAt, deliveryState = message.deliveryState, size = 13.dp)
    }
}

@Composable
private fun TextBubble(message: Message, shape: Shape) {
    val sent = message.isSent
    Text(
        text = message.body,
        style = MaterialTheme.typography.bodyLarge,
        color = if (sent) OnAccent else TextPrimary,
        modifier = Modifier
            .widthIn(max = MaxBubbleWidth)
            .clip(shape)
            .background(if (sent) Accent else Surface2)
            .padding(horizontal = RelaySpacing.md + 2.dp, vertical = RelaySpacing.sm + 2.dp),
    )
}

@Composable
private fun LocationCard(message: Message, shape: Shape) {
    val lat = message.lat ?: return
    val lng = message.lng ?: return
    val context = LocalContext.current
    val expiry = ChatTimeline.expiryLabel(message.expiryAt, System.currentTimeMillis())

    // No map tile: Relay has no map of its own (tiles would reveal the viewer's IP to a map server).
    // "Open in Maps" hands the point to the user's own maps app, on a deliberate tap.
    Column(
        modifier = Modifier
            .widthIn(max = MaxMediaWidth)
            .clip(shape)
            .background(Surface2)
            .clickable { openInMaps(context, lat, lng, message.pinLabel) }
            .padding(RelaySpacing.md + 2.dp),
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp).clip(CircleShape).background(Border),
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
            }
            Text(
                text = if (message.isSent) "You shared a location" else "Shared a location",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
        }
        if (!message.pinLabel.isNullOrBlank()) {
            Text(text = message.pinLabel, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
        Text(
            text = "${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lng)}",
            style = RelayDataStyle,
            color = TextSecondary,
        )
        if (expiry != null) {
            Text(text = expiry, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text(text = "Open in Maps", style = MaterialTheme.typography.labelLarge, color = Accent, modifier = Modifier.padding(top = RelaySpacing.xs))
    }
}

private fun openInMaps(context: Context, lat: Double, lng: Double, label: String?) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, GeoLinks.toUri(lat, lng, label)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
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
private fun ImageBubble(message: Message, shape: Shape) {
    val uri = message.mediaUri ?: return
    val displayFile = if (uri.startsWith("/")) rememberDisplayFile(uri) else null
    val model: Any? = if (uri.startsWith("/")) displayFile else Uri.parse(uri)

    Column(modifier = Modifier.widthIn(max = MaxMediaWidth).clip(shape).background(Surface2)) {
        AsyncImage(
            model = model,
            contentDescription = "Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        if (message.body.isNotEmpty()) MediaCaption(message.body)
    }
}

@Composable
private fun VideoBubble(message: Message, shape: Shape) {
    val path = message.mediaUri ?: return
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

    Column(modifier = Modifier.widthIn(max = MaxMediaWidth).clip(shape).background(Surface2)) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Video thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(MediaScrim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        if (message.body.isNotEmpty()) MediaCaption(message.body)
    }
}

@Composable
private fun MediaCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextPrimary,
        modifier = Modifier.padding(horizontal = RelaySpacing.md, vertical = RelaySpacing.sm),
    )
}
