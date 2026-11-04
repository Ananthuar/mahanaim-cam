
package com.mahanaim.cam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
import com.mahanaim.cam.network.SignalingClient
import com.mahanaim.cam.network.SignalingState
import com.mahanaim.cam.network.WebRTCManager
import com.mahanaim.cam.ui.theme.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MahanaimCAM/Camera"

@Composable
fun HomeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current

    var signalingState by remember {
        mutableStateOf<SignalingState>(SignalingState.Disconnected)
    }

    var cameraActive by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scannedDesktopUrl by remember { mutableStateOf<String?>(null) }
    var scanAfterPermission by remember { mutableStateOf(false) }

    var streamStatus by remember {
        mutableStateOf("Waiting for desktop connection")
    }

    // --------------------------------------------------
    // WEBRTC MESSAGE MANAGEMENT
    // --------------------------------------------------

    val webRtcManagerRef = remember {
        AtomicReference<WebRTCManager?>(null)
    }

    // Protects the manager and pending-message queue.
    val messageLock = remember {
        Any()
    }

    // Messages received before WebRTCManager is ready.
    val pendingMessages = remember {
        mutableListOf<JSONObject>()
    }

    val signalingClient = remember(context) {
        SignalingClient(
            context = context,

            onMessageReceived = { message ->
                synchronized(messageLock) {
                    val manager = webRtcManagerRef.get()

                    if (manager != null) {
                        Log.d(
                            TAG,
                            "Forwarding ${message.optString("type")} to WebRTC manager"
                        )

                        manager.handleSignalingMessage(message)
                    } else {
                        pendingMessages.add(message)

                        Log.d(
                            TAG,
                            "WebRTC manager not ready. Queued ${message.optString("type")}. Pending: ${pendingMessages.size}"
                        )
                    }
                }
            },

            onStateChanged = { newState ->
                Handler(Looper.getMainLooper()).post {
                    Log.d(
                        TAG,
                        "Signaling state changed: ${newState::class.simpleName}"
                    )

                    signalingState = newState
                }
            }
        )
    }

    // --------------------------------------------------
    // WEBRTC MANAGER LIFECYCLE
    // --------------------------------------------------

    LaunchedEffect(signalingState) {
        when (val state = signalingState) {

            is SignalingState.Paired -> {
                cameraActive = false
                isScanning = false

                streamStatus = "Paired. Waiting for video offer..."

                Log.d(
                    TAG,
                    "Pairing successful. Creating WebRTC manager for session ${state.sessionId}"
                )

                val manager = WebRTCManager(
                    context = context,
                    signalingClient = signalingClient,
                    onStatusChanged = { status ->
                        Handler(Looper.getMainLooper()).post {
                            streamStatus = status
                        }
                    }
                )

                synchronized(messageLock) {
                    val previousManager = webRtcManagerRef.getAndSet(manager)

                    if (previousManager != null) {
                        Log.d(
                            TAG,
                            "Stopping previous WebRTC manager"
                        )

                        previousManager.stop()
                    }

                    Log.d(
                        TAG,
                        "WebRTC manager is ready. Pending messages: ${pendingMessages.size}"
                    )

                    // Process queued messages in arrival order.
                    pendingMessages.forEach { message ->
                        Log.d(
                            TAG,
                            "Processing queued message: ${message.optString("type")}"
                        )

                        manager.handleSignalingMessage(message)
                    }

                    pendingMessages.clear()

                    Log.d(
                        TAG,
                        "Pending WebRTC message queue cleared"
                    )
                }
            }

            // Only terminal states should stop the active manager.
            is SignalingState.Disconnected,
            is SignalingState.Rejected,
            is SignalingState.Failed -> {

                Log.d(
                    TAG,
                    "Terminal signaling state. Releasing WebRTC manager."
                )

                val manager = synchronized(messageLock) {
                    pendingMessages.clear()
                    webRtcManagerRef.getAndSet(null)
                }

                manager?.stop()

                if (state is SignalingState.Disconnected) {
                    streamStatus = "Disconnected"
                }
            }

            // Connecting and AwaitingApproval are transient states.
            // Do not destroy an existing manager for these states.
            is SignalingState.Connecting,
            is SignalingState.AwaitingApproval -> {
                Log.d(
                    TAG,
                    "Transient signaling state. Keeping current WebRTC manager."
                )
            }
        }
    }

    // --------------------------------------------------
    // DISPOSAL
    // --------------------------------------------------

    DisposableEffect(signalingClient) {
        onDispose {
            Log.d(TAG, "HomeScreen disposed. Releasing resources.")

            val manager = synchronized(messageLock) {
                pendingMessages.clear()
                webRtcManagerRef.getAndSet(null)
            }

            manager?.stop()
            signalingClient.disconnect()
        }
    }

    // --------------------------------------------------
    // CAMERA STATE
    // --------------------------------------------------

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

    // --------------------------------------------------
    // MAIN UI
    // --------------------------------------------------

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

            ConnectionStatusCard(
                isConnected = signalingState is SignalingState.Paired
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                        text = when {
                            signalingState is SignalingState.Paired ->
                                "Camera is being used for WebRTC streaming"

                            scannedDesktopUrl != null ->
                                "Desktop QR code detected!"

                            isScanning ->
                                "Starting QR scanner..."

                            else ->
                                "Camera preview will appear here"
                        },
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            permissionMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // --------------------------------------------------
            // START CAMERA
            // --------------------------------------------------

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
                enabled = !cameraActive &&
                    !isScanning &&
                    signalingState !is SignalingState.Paired,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo500
                )
            ) {
                Text("Start Camera", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --------------------------------------------------
            // STOP CAMERA
            // --------------------------------------------------

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

            // --------------------------------------------------
            // SWITCH CAMERA
            // --------------------------------------------------

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

            // --------------------------------------------------
            // SCAN DESKTOP QR
            // --------------------------------------------------

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
                enabled = !isScanning &&
                    signalingState !is SignalingState.Paired,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Scan Desktop QR Code")
            }

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

            // --------------------------------------------------
            // CONNECTION CONTROLS
            // --------------------------------------------------

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

                Spacer(modifier = Modifier.height(10.dp))

                when (val state = signalingState) {

                    is SignalingState.Disconnected -> {
                        Button(
                            onClick = {
                                signalingClient.connect(url)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Indigo500
                            )
                        ) {
                            Text("Connect to Desktop")
                        }
                    }

                    is SignalingState.Connecting -> {
                        CircularProgressIndicator(color = Indigo500)

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            "Connecting to desktop...",
                            color = TextSecondary
                        )
                    }

                    is SignalingState.AwaitingApproval -> {
                        CircularProgressIndicator(color = Indigo500)

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            "Connected. Waiting for pairing response...",
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }

                    is SignalingState.Paired -> {
                        Text(
                            "Successfully paired!",
                            color = StatusOnline,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Session: ${state.sessionId.take(8)}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                signalingClient.disconnect()
                                signalingState = SignalingState.Disconnected
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect")
                        }
                    }

                    is SignalingState.Rejected -> {
                        Text(
                            "Pairing rejected: ${state.reason}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = {
                                signalingClient.connect(url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Try Again")
                        }
                    }

                    is SignalingState.Failed -> {
                        Text(
                            "Connection failed: ${state.reason}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = {
                                signalingClient.connect(url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry Connection")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (signalingState is SignalingState.Paired) {
                    "WebRTC: $streamStatus"
                } else {
                    "Local camera preview\nWaiting for Windows Studio connection"
                },
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = TextMuted,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// --------------------------------------------------
// APP LOGO
// --------------------------------------------------

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

// --------------------------------------------------
// CONNECTION STATUS CARD
// --------------------------------------------------

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
                        "Paired with Windows Studio"
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

// --------------------------------------------------
// LOCAL CAMERA PREVIEW
// --------------------------------------------------

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

                Log.d(
                    TAG,
                    "Camera preview started: lensFacing=$lensFacing"
                )
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Failed to start camera preview",
                    exception
                )
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
