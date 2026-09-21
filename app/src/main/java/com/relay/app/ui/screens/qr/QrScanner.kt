package com.relay.app.ui.screens.qr

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.sms.QrContactExchange
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.ui.components.ConfirmDialog
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.theme.RelayShapeTokens
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.util.QrContactCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// CameraX's ImageProxy.image is an experimental API; the analyzer below opts in explicitly. Lint does not
// recognise Kotlin's @OptIn for this marker inside the AndroidView factory lambda, so it is suppressed here.
@android.annotation.SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
internal fun ScanTab(onConnected: (Long) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
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

    fun onDecoded(value: QrContactCode.ScannedContact) {
        // Guard against scanning your own code (e.g. a screenshot / second device): it would
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
                        errorMsg = "Couldn't add this person. Try again."
                    }
            }
        }
    }

    if (!hasCameraPermission) {
        EmptyState(
            title = "Relay needs your camera",
            message = "It is only used to read a friend's code. Nothing is recorded or uploaded.",
            actionLabel = "Allow camera",
            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Held so the async camera-provider callback can be told to abort if the tab is disposed before
    // it fires (otherwise it binds the camera *after* onDispose ran unbindAll, leaving it running),
    // and so the ML Kit scanner (a Closeable native resource) gets released.
    val disposed = remember { AtomicBoolean(false) }
    val scannerRef = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val onDecodedState = rememberUpdatedState<(String) -> Unit> { raw ->
            if (!scanned) {
                val decoded = QrContactCode.decode(raw)
                if (decoded != null) onDecoded(decoded) else errorMsg = "That is not a Relay code."
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
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
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
                // A scrim over the camera preview: dark whatever the theme.
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connecting", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }

        errorMsg?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(RelaySpacing.xl)
                    .clip(RelayShapeTokens.control)
                    .background(Surface1)
                    .padding(horizontal = RelaySpacing.lg, vertical = RelaySpacing.md),
            ) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            // Auto-clear so the scanner can retry.
            LaunchedEffect(msg) {
                delay(2500)
                errorMsg = null
            }
        }
    }

    pendingScan?.let { scan ->
        val idLine = scan.nostrPubkeyHex?.let { "Relay ID ${it.take(8)}…${it.takeLast(4)}" } ?: (scan.phone ?: "")
        val actionLine = if (scan.nostrPubkeyHex != null) {
            "This saves them and sends them your key over the internet."
        } else {
            "This is an old code: it saves them and texts them your key by SMS."
        }
        ConfirmDialog(
            title = "Add ${scan.name}?",
            message = "$idLine\n\n$actionLine",
            confirmLabel = "Add ${scan.name}",
            onConfirm = { confirmAdd(scan) },
            onDismiss = { pendingScan = null; scanned = false },
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
