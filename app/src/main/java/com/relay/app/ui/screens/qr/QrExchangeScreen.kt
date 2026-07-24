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
    var phone by remember { mutableStateOf(prefs.myPhone) }
    var profileSet by remember { mutableStateOf(prefs.myName.isNotBlank() && prefs.myPhone.isNotBlank()) }

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
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            com.relay.app.ui.components.PhoneNumberField(
                onE164Change = { phone = it },
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    prefs.myName = name.trim()
                    prefs.myPhone = phone.trim()
                    profileSet = name.isNotBlank() && phone.isNotBlank()
                },
                enabled = name.isNotBlank() && phone.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
            ) {
                Text("Generate my code")
            }
        } else {
            val myKey = remember { RelayCrypto.myPublicKeyBase64(context) }
            if (myKey == null) {
                Text(
                    "Couldn't generate your encryption key. Try again shortly.",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                )
            } else {
                val payload = remember(prefs.myPhone, prefs.myName, myKey) { QrContactCode.encode(prefs.myPhone, prefs.myName, myKey) }
                val bitmap = remember(payload) { encodeQrBitmap(payload, 800) }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Your QR code",
                        modifier = Modifier
                            .size(260.dp)
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(12.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    Text(prefs.myName, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 16.sp)
                }
                TextButton(onClick = { profileSet = false }) {
                    Text("Edit profile", color = Accent, fontFamily = IbmPlexSans)
                }
            }
        }
    }
}

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

    fun handleDecoded(value: QrContactCode.ScannedContact) {
        scanned = true
        connecting = true
        coroutineScope.launch(Dispatchers.IO) {
            val db = RelayDbHelper(context)
            val contactRepo = ContactRepository(db)
            val contact = QrContactExchange.onScanned(context, contactRepo, value)
            withContext(Dispatchers.Main) {
                onConnected(contact.id)
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

    Box(modifier = Modifier.fillMaxSize()) {
        val onDecodedState = rememberUpdatedState<(String) -> Unit> { raw ->
            if (!scanned) {
                QrContactCode.decode(raw)?.let { handleDecoded(it) }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val scannerOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(scannerOptions)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(ctx),
                        QrAnalyzer(scanner) { value -> onDecodedState.value(value) },
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
                runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
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
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    null
}
