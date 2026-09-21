package com.relay.app.ui.screens.qr

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.relay.app.nfc.NfcPairing
import com.relay.app.pairing.PairingCoordinator
import com.relay.app.pairing.PairingEvent
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.components.PrimaryButton
import com.relay.app.ui.components.RelayTextField
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SecondaryButton
import com.relay.app.ui.components.SegmentedControl
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.RelayShapeTokens
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class QrTab { SCAN, MY_CODE }

/** Meeting in person: scan a friend's code, then let them scan yours. This is what "verified" means. */
@Composable
fun QrExchangeScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { RelayPreferences(context) }
    var selectedTab by remember { mutableStateOf(QrTab.SCAN) }
    var pendingContactId by remember { mutableStateOf<Long?>(null) }
    // True when the other person also said yes, so both sides are already verified.
    var mutual by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        PairingCoordinator.events.collect { event ->
            if (event is PairingEvent.Verified) {
                pendingContactId = event.contactId
                mutual = true
                selectedTab = QrTab.MY_CODE
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        RelayTopBar(title = "Meet in person", onBack = { navController.popBackStack() })
        SegmentedControl(
            options = listOf(QrTab.SCAN to "Scan their code", QrTab.MY_CODE to "Show my code"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
        )

        when (selectedTab) {
            QrTab.SCAN -> ScanTab(
                onConnected = { contactId ->
                    // The one-sided flow (an older code without a pairing field).
                    pendingContactId = contactId
                    mutual = false
                    selectedTab = QrTab.MY_CODE
                },
            )
            QrTab.MY_CODE -> MyCodeTab(
                prefs = prefs,
                pendingContactId = pendingContactId,
                mutual = mutual,
                onProceedToChat = { contactId ->
                    navController.navigate(Screen.Chat.routeFor(contactId)) {
                        popUpTo(Screen.QrExchange.route) { inclusive = true }
                    }
                },
            )
        }
    }
}

/**
 * The one animated moment in the app: after a scan, the new contact's glyph settles into place and the
 * frame goes from dashed to solid. With animations switched off in system settings it just appears.
 */
@Composable
private fun PairedPanel(contact: Contact, mutual: Boolean, onProceedToChat: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(contact.id) {
        if (!reduceMotion) progress.animateTo(1f, tween(durationMillis = 700))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RelayShapeTokens.bubble)
            .background(Surface1)
            .padding(RelaySpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.md),
    ) {
        ContactGlyph(
            contact,
            size = 88.dp,
            modifier = Modifier.scale(0.85f + 0.15f * progress.value).alpha(0.3f + 0.7f * progress.value),
        )
        Text(
            if (mutual) "You and ${contact.name} are verified" else "You scanned ${contact.name}",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Text(
            if (mutual) "You both said yes, so each of you knows you are talking to the right person."
            else "Now let them scan your code below, so they can add you too.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        SecondaryButton("Start chatting now", onProceedToChat, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MyCodeTab(
    prefs: RelayPreferences,
    pendingContactId: Long? = null,
    mutual: Boolean = false,
    onProceedToChat: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(prefs.myName) }
    var profileSet by remember { mutableStateOf(prefs.myName.isNotBlank()) }
    val nfc = remember { NfcPairing.availability(context) }
    // While this screen is open the phone answers taps with its code; when it closes, it stops.
    DisposableEffect(Unit) {
        val activity = NfcPairing.activityOf(context)
        if (activity != null) NfcPairing.startServing(activity)
        onDispose { activity?.let { NfcPairing.stopServing(it) } }
    }
    val pending by produceState<Contact?>(null, pendingContactId) {
        value = pendingContactId?.let { ContactRepository(RelayDbHelper(context)).getById(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(RelaySpacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.lg),
    ) {
        pending?.let { PairedPanel(it, mutual, onProceedToChat = { onProceedToChat(it.id) }) }

        if (!profileSet) {
            Text(
                "Pick the name people see when they scan your code. You can change it later.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            RelayTextField(value = name, onValueChange = { name = it.take(30) }, label = "Your name", modifier = Modifier.fillMaxWidth())
            PrimaryButton(
                "Show my code",
                {
                    prefs.myName = name.trim()
                    profileSet = name.isNotBlank()
                },
                Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
            )
        } else {
            // Keystore keypair generation + QR bitmap rendering are both expensive; run them off the
            // main thread (this composes right after the post-scan auto tab-flip, so a main-thread
            // stall here would freeze at the worst moment). null bitmap after done = failure.
            var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var qrFailed by remember { mutableStateOf(false) }
            val nonce by PairingCoordinator.shownNonce.collectAsState()
            // An unused code rotates, so a code someone photographed earlier stops working.
            LaunchedEffect(Unit) {
                PairingCoordinator.refreshShownNonce() // the app may have been open longer than a code lives
                while (true) {
                    delay(60_000)
                    PairingCoordinator.refreshShownNonce()
                }
            }
            LaunchedEffect(prefs.myName, nonce) {
                qrFailed = false
                val bmp = withContext(Dispatchers.Default) {
                    val myKey = RelayCrypto.myPublicKeyBase64(context) ?: return@withContext null
                    val mySigningKey = RelayCrypto.mySigningPublicKeyBase64(context)
                    val myNostr = runCatching { NostrIdentity.publicKeyHex(context) }.getOrNull() ?: return@withContext null
                    val code = QrContactCode.encode(
                        name = prefs.myName,
                        nostrPubkeyHex = myNostr,
                        publicKeyBase64 = myKey,
                        signingPublicKeyBase64 = mySigningKey,
                        relayHints = prefs.nostrRelays.take(3),
                        pairNonce = nonce,
                    )
                    NfcPairing.payload = code // the same code, served to a phone that is held against this one
                    encodeQrBitmap(code, 800)
                }
                if (bmp == null) qrFailed = true else qrBitmap = bmp
            }

            val bmp = qrBitmap
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Your code",
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RelayShapeTokens.bubble)
                            // A QR code needs a white quiet zone to scan, in both themes.
                            .background(Color.White)
                            .padding(RelaySpacing.md),
                    )
                    Text(prefs.myName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    when (nfc) {
                        NfcPairing.Availability.ON ->
                            Text("Or hold the back of your phone against theirs.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        NfcPairing.Availability.OFF ->
                            Text("Turn on NFC in your phone's settings to tap phones together.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        NfcPairing.Availability.NONE -> Unit
                    }
                    SecondaryButton("Change name", { profileSet = false })
                }
                qrFailed -> Text("Couldn't make your code. Go back and try again.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                else -> Text("Making your code", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

private fun encodeQrBitmap(content: String, size: Int): Bitmap? = try {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    // Fill a plain IntArray and hand it to createBitmap in one shot, rather than size*size
    // individual setPixel() JNI calls (640k of them at 800px).
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
} catch (e: Exception) {
    null
}
