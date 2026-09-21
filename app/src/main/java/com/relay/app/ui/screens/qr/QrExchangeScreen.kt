package com.relay.app.ui.screens.qr

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.sms.QrContactExchange
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class QrTab { SCAN, MY_CODE }

@Composable
fun QrExchangeScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { RelayPreferences(context) }
    var selectedTab by remember { mutableStateOf(QrTab.SCAN) }
    var pendingContactId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            RelayTopBar(title = "QR Contact Exchange", onBack = { navController.popBackStack() })
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Surface1,
                contentColor = Accent,
            ) {
                Tab(
                    selected = selectedTab == QrTab.SCAN,
                    onClick = { selectedTab = QrTab.SCAN },
                    text = { Text("SCAN", fontFamily = IbmPlexSans) },
                    icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == QrTab.MY_CODE,
                    onClick = { selectedTab = QrTab.MY_CODE },
                    text = { Text("MY CODE", fontFamily = IbmPlexSans) },
                    icon = { Icon(Icons.Outlined.QrCode, contentDescription = null) },
                )
            }

            when (selectedTab) {
                QrTab.SCAN -> ScanTab(
                    onConnected = { contactId ->
                        pendingContactId = contactId
                        selectedTab = QrTab.MY_CODE
                    },
                )
                QrTab.MY_CODE -> MyCodeTab(
                    prefs = prefs,
                    pendingContactId = pendingContactId,
                    onProceedToChat = { contactId ->
                        navController.navigate(Screen.Chat.routeFor(contactId)) {
                            popUpTo(Screen.QrExchange.route) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MyCodeTab(
    prefs: RelayPreferences,
    pendingContactId: Long? = null,
    onProceedToChat: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(prefs.myName) }
    var profileSet by remember { mutableStateOf(prefs.myName.isNotBlank()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (pendingContactId != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Scanned! Have them scan your code below to finish the secure connection.",
                    color = TextPrimary,
                    fontFamily = IbmPlexSans,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(onClick = { onProceedToChat(pendingContactId) }) {
                    Text("Skip — start chatting now", color = Accent, fontFamily = IbmPlexSans)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }

        if (!profileSet) {
            Text(
                "Set up your profile once — this is what people see when they scan your code.",
                color = TextSecondary,
                fontFamily = IbmPlexSans,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name", fontFamily = IbmPlexSans) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    prefs.myName = name.trim()
                    profileSet = name.isNotBlank()
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
            ) {
                Text("Generate my code")
            }
        } else {
            // Keystore keypair generation + QR bitmap rendering are both expensive; run them off the
            // main thread (this composes right after the post-scan auto tab-flip, so a main-thread
            // stall here would freeze at the worst moment). null bitmap after done = failure.
            var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var qrFailed by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(prefs.myName) {
                qrFailed = false
                qrBitmap = null
                val bmp = withContext(Dispatchers.Default) {
                    val myKey = RelayCrypto.myPublicKeyBase64(context) ?: return@withContext null
                    val mySigningKey = RelayCrypto.mySigningPublicKeyBase64(context)
                    val myNostr = runCatching { NostrIdentity.publicKeyHex(context) }.getOrNull()
                        ?: return@withContext null
                    encodeQrBitmap(
                        QrContactCode.encode(
                            name = prefs.myName,
                            nostrPubkeyHex = myNostr,
                            publicKeyBase64 = myKey,
                            signingPublicKeyBase64 = mySigningKey,
                            relayHints = prefs.nostrRelays.take(3),
                        ),
                        800,
                    )
                }
                if (bmp == null) qrFailed = true else qrBitmap = bmp
            }

            val bmp = qrBitmap
            when {
                bmp != null -> {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Your QR code",
                        modifier = Modifier
                            .size(260.dp)
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(12.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    Text(prefs.myName, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 16.sp)
                    TextButton(onClick = { profileSet = false }) {
                        Text("Edit profile", color = Accent, fontFamily = IbmPlexSans)
                    }
                }
                qrFailed -> Text(
                    "Couldn't generate your code. Try again.",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                )
                else -> Text("Generating your code…", color = TextSecondary, fontFamily = IbmPlexSans)
            }
        }
    }
}

// CameraX's ImageProxy.image is an experimental API; the analyzer below opts in explicitly. Lint does not
// recognise Kotlin's @OptIn for this marker inside the AndroidView factory lambda, so it is suppressed here.
@android.annotation.SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalGetImage::class)
@Composable
private fun ScanTab(onConnected: (Long) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var scanned by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingScan by remember { mutableStateOf<QrContactCode.ScannedContact?>(null) }
    val prefs = remember { RelayPreferences(context) }

    fun onDecoded(value: QrContactCode.ScannedContact) {
        // Guard against scanning your own code (e.g. a screenshot / second device) — it would
        // create a self-contact and send a handshake to ourselves.
        if (!QrContactExchange.canPair(value)) {
            errorMsg = "That's an older Relay code. Ask them to update the app."
            return
        }
        val myNostr = runCatching { NostrIdentity.publicKeyHex(context) }.getOrNull()
        if (value.nostrPubkeyHex != null && value.nostrPubkeyHex == myNostr) {
            errorMsg = "That's your own code."
            return
        }
        // Stop the scanner and ask for confirmation before saving and sending our key to the
        // scanned contact (don't act on a scanned code silently).
        scanned = true
        pendingScan = value
    }

    fun confirmAdd(value: QrContactCode.ScannedContact) {
        pendingScan = null
        connecting = true
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val db = RelayDbHelper(context)
                val contactRepo = ContactRepository(db)
                QrContactExchange.onScanned(context, contactRepo, value)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { onConnected(it.id) }
                    .onFailure {
                        connecting = false
                        scanned = false
                        errorMsg = "Couldn't add contact. Try again."
                    }
            }
        }
    }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Camera permission is needed to scan a QR code.",
                color = TextSecondary,
                fontFamily = IbmPlexSans,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
            ) {
                Text("Grant permission")
            }
        }
        return
    }

    // Held so the async camera-provider callback can be told to abort if the tab is disposed before
    // it fires (otherwise it binds the camera *after* onDispose ran unbindAll, leaving it running),
    // and so the ML Kit scanner (a Closeable native resource) gets released.
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val scannerRef = remember { BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    ) }

    Box(modifier = Modifier.fillMaxSize()) {
        val onDecodedState = rememberUpdatedState<(String) -> Unit> { raw ->
            if (!scanned) {
                val decoded = QrContactCode.decode(raw)
                if (decoded != null) onDecoded(decoded) else errorMsg = "Not a Relay code."
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    if (disposed.get()) return@addListener
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(ctx),
                        QrAnalyzer(scannerRef) { value -> onDecodedState.value(value) },
                    )
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        // Camera bind can fail if the screen is torn down mid-init; nothing to
                        // recover here, the user can just navigate back and retry.
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        DisposableEffect(Unit) {
            onDispose {
                disposed.set(true)
                runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                runCatching { scannerRef.close() }
            }
        }

        if (connecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connecting…", color = TextPrimary, fontFamily = IbmPlexSans)
            }
        }

        errorMsg?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Surface1)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(msg, color = TextPrimary, fontFamily = IbmPlexSans)
            }
            // Auto-clear so the scanner can retry.
            androidx.compose.runtime.LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                errorMsg = null
            }
        }
    }

    pendingScan?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingScan = null; scanned = false },
            containerColor = Surface1,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Add contact?", fontFamily = IbmPlexSans) },
            text = {
                val idLine = scan.nostrPubkeyHex
                    ?.let { "Relay ID ${it.take(8)}…${it.takeLast(4)}" }
                    ?: (scan.phone ?: "")
                val actionLine = if (scan.nostrPubkeyHex != null) {
                    "This saves them and sends them your key over the internet."
                } else {
                    "Legacy code: this saves them and texts them your key by SMS."
                }
                Text(
                    "${scan.name}\n$idLine\n\n$actionLine",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAdd(scan) }) {
                    Text("Add", color = Accent, fontFamily = IbmPlexSans)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingScan = null; scanned = false }) {
                    Text("Cancel", color = TextSecondary, fontFamily = IbmPlexSans)
                }
            },
        )
    }
}

@ExperimentalGetImage
private class QrAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val onDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let(onDetected)
            }
            .addOnCompleteListener { imageProxy.close() }
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
