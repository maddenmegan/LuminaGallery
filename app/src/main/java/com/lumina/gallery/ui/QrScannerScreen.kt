package com.lumina.gallery.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.lumina.gallery.data.AiEngine
import com.lumina.gallery.model.QrItemData
import java.util.concurrent.Executors

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun LiveQrScannerScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPerm = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var torchOn by remember { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var detectedQr by remember { mutableStateOf<QrItemData?>(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasCameraPerm) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.QrCodeScanner, null, tint = LuminaCyan, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Camera permission required for Live QR Scan", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Camera Permission")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onClose) { Text("Back") }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        try {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && detectedQr == null) {
                                    val input = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(input)
                                        .addOnSuccessListener { barcodes ->
                                            val first = barcodes.firstOrNull()?.let { AiEngine.mapBarcodeToQrItem(it) }
                                            if (first != null) detectedQr = first
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            provider.unbindAll()
                            cameraRef = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (_: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Center Scan Reticle
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .align(Alignment.Center)
                    .border(2.5.dp, LuminaCyan, RoundedCornerShape(24.dp))
            )

            // Top Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Live QR & Barcode Scanner", color = Color.White, fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    torchOn = !torchOn
                    cameraRef?.cameraControl?.enableTorch(torchOn)
                }) {
                    Icon(
                        if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        "Flash",
                        tint = if (torchOn) LuminaAmber else Color.White
                    )
                }
            }

            Text(
                text = "Point at any QR code, Wi-Fi code, or barcode",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 42.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    val found = detectedQr
    if (found != null) {
        QrActionBottomSheet(
            qrItems = listOf(found),
            onDismiss = { detectedQr = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrActionBottomSheet(
    qrItems: List<QrItemData>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun copyToClip(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LuminaSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "QR / Barcode Detected (${qrItems.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = LuminaCyan
            )

            qrItems.forEach { qr ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = LuminaCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(qr.typeLabel, style = MaterialTheme.typography.labelMedium, color = LuminaCyan, fontWeight = FontWeight.Bold)
                        Text(qr.displayValue, color = Color.White, style = MaterialTheme.typography.bodyMedium)

                        if (qr.wifiSsid != null) {
                            Text(
                                text = "SSID: ${qr.wifiSsid}\nPassword: ${qr.wifiPassword ?: "(None)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = LuminaGreen
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (qr.url != null) {
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qr.url)))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Open Link", fontWeight = FontWeight.Bold)
                                }
                            } else if (qr.wifiPassword != null) {
                                Button(
                                    onClick = { copyToClip("Wi-Fi Password", qr.wifiPassword) },
                                    colors = ButtonDefaults.buttonColors(containerColor = LuminaGreen, contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Copy Password", fontWeight = FontWeight.Bold)
                                }
                            } else if (qr.phone != null) {
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${qr.phone}")))
                                        } catch (_: Exception) {}
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Call")
                                }
                            } else if (qr.email != null) {
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${qr.email}")))
                                        } catch (_: Exception) {}
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Email, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Email")
                                }
                            }

                            OutlinedButton(
                                onClick = { copyToClip("QR", qr.rawValue) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Copy")
                            }

                            OutlinedButton(
                                onClick = {
                                    val s = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, qr.rawValue)
                                    }
                                    context.startActivity(Intent.createChooser(s, "Share QR"))
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}
