
package com.mahanaim.cam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.mahanaim.cam.ui.theme.*

private const val TAG = "MahanaimCAM/Camera"

@Composable
fun HomeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current

    var cameraActive by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scannedDesktopUrl by remember { mutableStateOf<String?>(null) }
    var scanAfterPermission by remember { mutableStateOf(false) }

    var lensFacing by remember {
        mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionMessage by remember {
        mutableStateOf<String?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted

        if (granted) {
            permissionMessage = null

            if (scanAfterPermission) {
                isScanning = true
                cameraActive = false
                scanAfterPermission = false
            } else {
                cameraActive = true
            }
        } else {
            scanAfterPermission = false
            permissionMessage = "Camera permission is required."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            AppLogo()

            Spacer(modifier = Modifier.height(20.dp))

            ConnectionStatusCard(isConnected = false)

            Spacer(modifier = Modifier.height(16.dp))

            // Camera preview or QR scanner
            if (isScanning && hasPermission) {
                QrScannerPreview(
                    onQrCodeScanned = { url ->
                        scannedDesktopUrl = url
                        isScanning = false
                        cameraActive = false

                        Log.d(TAG, "Desktop QR detected: $url")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else if (cameraActive && hasPermission) {
                CameraPreview(
                    lensFacing = lensFacing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF171A22)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (scannedDesktopUrl != null) {
                            "Desktop QR code detected!"
                        } else if (isScanning) {
                            "Starting QR scanner..."
                        } else {
                            "Camera preview will appear here"
                        },
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Permission error
            permissionMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Start camera
            Button(
                onClick = {
                    scannedDesktopUrl = null

                    if (hasPermission) {
                        cameraActive = true
                        isScanning = false
                    } else {
                        scanAfterPermission = false
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = !cameraActive && !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo500
                )
            ) {
                Text(
                    "Start Camera",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stop camera
            OutlinedButton(
                onClick = {
                    cameraActive = false
                },
                enabled = cameraActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Stop Camera")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Switch front/back camera
            OutlinedButton(
                onClick = {
                    lensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                },
                enabled = cameraActive && !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Switch Camera")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scan desktop QR code
            Button(
                onClick = {
                    scannedDesktopUrl = null
                    permissionMessage = null

                    if (hasPermission) {
                        cameraActive = false
                        isScanning = true
                    } else {
                        scanAfterPermission = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Scan Desktop QR Code")
            }

            // Cancel scanning
            if (isScanning) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        isScanning = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Cancel Scanning")
                }
            }

            // Display detected desktop address
            scannedDesktopUrl?.let { url ->
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Desktop detected!",
                    color = StatusOnline,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = url,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Local camera preview\nStreaming will be implemented next",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = TextMuted,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppLogo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Indigo500, Purple500)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("📷", fontSize = 30.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "MAHANAIM CAM",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            letterSpacing = 1.sp
        )

        Text(
            text = "Church Webcam",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
    }
}

@Composable
private fun ConnectionStatusCard(isConnected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BackgroundSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) StatusOnline else StatusOffline
                    )
            )

            Column {
                Text(
                    text = if (isConnected) "Connected" else "Not Connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )

                Text(
                    text = if (isConnected) {
                        "Streaming to Windows Studio"
                    } else {
                        "Waiting for Windows connection"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    lensFacing: Int,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, lensFacing, previewView) {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        var cameraProvider: ProcessCameraProvider? = null
        var cameraBound = false
        var disposed = false

        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            if (disposed) return@addListener

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview
                )

                cameraBound = true

                Log.d(TAG, "Camera preview started: lensFacing=$lensFacing")
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start camera preview", exception)
            }
        }, mainExecutor)

        onDispose {
            disposed = true

            if (cameraBound) {
                cameraProvider?.unbindAll()
                Log.d(TAG, "Camera preview stopped")
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}
