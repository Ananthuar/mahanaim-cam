
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.mahanaim.cam.network.SignalingClient
import com.mahanaim.cam.network.SignalingState
import com.mahanaim.cam.network.WebRTCManager
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MahanaimCAM/Camera"

// --------------------------------------------------
// MAHANAIM CAM DESIGN COLORS
// --------------------------------------------------

private val CamBackground = Color(0xFF0C101B)
private val CamSurface = Color(0xFF171E2D)
private val CamSurfaceLight = Color(0xFF202B3B)
private val CamGold = Color(0xFFD5B66D)
private val CamTeal = Color(0xFF268C91)
private val CamText = Color(0xFFF4F3EF)
private val CamMuted = Color(0xFF9AA7B8)
private val CamGreen = Color(0xFF43D18A)
private val CamRed = Color(0xFFFF5364)

// --------------------------------------------------
// MAIN SCREEN
// --------------------------------------------------

@Composable
fun HomeScreen() {
    val context = LocalContext.current

    var signalingState by remember {
        mutableStateOf<SignalingState>(SignalingState.Disconnected)
    }

    var cameraActive by remember {
        mutableStateOf(false)
    }

    var isScanning by remember {
        mutableStateOf(false)
    }

    var scannedDesktopUrl by remember {
        mutableStateOf<String?>(null)
    }

    var scanAfterPermission by remember {
        mutableStateOf(false)
    }

    var permissionMessage by remember {
        mutableStateOf<String?>(null)
    }

    var streamStatus by remember {
        mutableStateOf("Waiting for desktop connection")
    }

    var activeManager by remember {
        mutableStateOf<WebRTCManager?>(null)
    }

    val webRtcManagerRef = remember {
        AtomicReference<WebRTCManager?>(null)
    }

    val messageLock = remember {
        Any()
    }

    val pendingMessages = remember {
        mutableListOf<JSONObject>()
    }

    // --------------------------------------------------
    // CAMERA PERMISSION
    // --------------------------------------------------

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
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
    // SIGNALING CLIENT
    // --------------------------------------------------

    val signalingClient = remember(context) {
        SignalingClient(
            context = context,

            onMessageReceived = { message ->
                synchronized(messageLock) {
                    val manager = webRtcManagerRef.get()

                    if (manager != null) {
                        Log.d(
                            TAG,
                            "Forwarding ${message.optString("type")} to WebRTC"
                        )

                        manager.handleSignalingMessage(message)
                    } else {
                        pendingMessages.add(message)

                        Log.d(
                            TAG,
                            "Queued ${message.optString("type")}. " +
                                "Pending=${pendingMessages.size}"
                        )
                    }
                }
            },

            onStateChanged = { newState ->
                Handler(Looper.getMainLooper()).post {
                    Log.d(
                        TAG,
                        "Signaling state: ${newState::class.simpleName}"
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
                isScanning = false
                cameraActive = false
                streamStatus = "Paired. Preparing camera..."

                Log.d(TAG, "Pairing successful: ${state.sessionId}")

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
                    val previousManager =
                        webRtcManagerRef.getAndSet(manager)

                    previousManager?.stop()

                    activeManager = manager

                    Log.d(
                        TAG,
                        "WebRTC manager ready. " +
                            "Pending messages=${pendingMessages.size}"
                    )

                    pendingMessages.forEach { message ->
                        manager.handleSignalingMessage(message)
                    }

                    pendingMessages.clear()
                }
            }

            is SignalingState.Disconnected,
            is SignalingState.Rejected,
            is SignalingState.Failed -> {

                val manager = synchronized(messageLock) {
                    pendingMessages.clear()
                    webRtcManagerRef.getAndSet(null)
                }

                manager?.stop()
                activeManager = null

                if (state is SignalingState.Disconnected) {
                    streamStatus = "Disconnected"
                }
            }

            is SignalingState.Connecting,
            is SignalingState.AwaitingApproval -> {
                Log.d(TAG, "Keeping current WebRTC manager")
            }
        }
    }

    // --------------------------------------------------
    // DISPOSE
    // --------------------------------------------------

    DisposableEffect(signalingClient) {
        onDispose {
            Log.d(TAG, "HomeScreen disposed")

            val manager = synchronized(messageLock) {
                pendingMessages.clear()
                webRtcManagerRef.getAndSet(null)
            }

            manager?.stop()
            signalingClient.disconnect()
        }
    }

    // --------------------------------------------------
    // MAIN CONTENT
    // --------------------------------------------------

    val isPaired = signalingState is SignalingState.Paired

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF151D2D),
                        CamBackground,
                        Color(0xFF101722)
                    )
                )
            )
    ) {
        if (isPaired) {
            LiveCameraStudio(
                manager = activeManager,
                streamStatus = streamStatus,
                sessionId = (signalingState as? SignalingState.Paired)
                    ?.sessionId ?: "",
                onDisconnect = {
                    signalingClient.disconnect()
                    signalingState = SignalingState.Disconnected
                }
            )
        } else {
            ConnectionScreen(
                cameraActive = cameraActive,
                isScanning = isScanning,
                hasPermission = hasPermission,
                scannedDesktopUrl = scannedDesktopUrl,
                permissionMessage = permissionMessage,
                signalingState = signalingState,
                streamStatus = streamStatus,

                onStartCamera = {
                    if (hasPermission) {
                        cameraActive = true
                        isScanning = false
                    } else {
                        scanAfterPermission = false
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                },

                onStopCamera = {
                    cameraActive = false
                },

                onStartScanning = {
                    permissionMessage = null

                    if (hasPermission) {
                        cameraActive = false
                        isScanning = true
                    } else {
                        scanAfterPermission = true
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                },

                onStopScanning = {
                    isScanning = false
                },

                onQrScanned = { url ->
                    scannedDesktopUrl = url
                    isScanning = false
                    cameraActive = false

                    Log.d(TAG, "Desktop QR detected: $url")
                },

                onConnect = { url ->
                    signalingClient.connect(url)
                },

                onRetry = { url ->
                    signalingClient.connect(url)
                }
            )
        }
    }
}

// --------------------------------------------------
// CONNECTION SCREEN
// --------------------------------------------------

@Composable
private fun ConnectionScreen(
    cameraActive: Boolean,
    isScanning: Boolean,
    hasPermission: Boolean,
    scannedDesktopUrl: String?,
    permissionMessage: String?,
    signalingState: SignalingState,
    streamStatus: String,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit,
    onQrScanned: (String) -> Unit,
    onConnect: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        BrandHeader()

        Spacer(modifier = Modifier.height(24.dp))

        ConnectionStatusCard(
            signalingState = signalingState
        )

        Spacer(modifier = Modifier.height(18.dp))

        // CAMERA / QR PREVIEW

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black)
        ) {
            when {
                isScanning && hasPermission -> {
                    QrScannerPreview(
                        onQrCodeScanned = onQrScanned,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                cameraActive && hasPermission -> {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "📷",
                            fontSize = 46.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Your camera studio starts here",
                            color = CamText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Preview your camera or scan the desktop QR code.",
                            color = CamMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (cameraActive && !isScanning) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    color = Color(0xCC111827),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "CAMERA PREVIEW",
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        ),
                        color = CamGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        permissionMessage?.let {
            Text(
                text = it,
                color = CamRed,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // CAMERA ACTIONS

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StudioActionButton(
                text = if (cameraActive) "Stop Preview" else "Preview Camera",
                modifier = Modifier.weight(1f),
                enabled = !isScanning &&
                    signalingState !is SignalingState.Connecting &&
                    signalingState !is SignalingState.AwaitingApproval,
                onClick = {
                    if (cameraActive) {
                        onStopCamera()
                    } else {
                        onStartCamera()
                    }
                }
            )

            StudioActionButton(
                text = if (isScanning) "Cancel Scan" else "Scan Desktop",
                modifier = Modifier.weight(1f),
                enabled = signalingState !is SignalingState.Paired,
                onClick = {
                    if (isScanning) {
                        onStopScanning()
                    } else {
                        onStartScanning()
                    }
                },
                highlighted = true
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // PAIRING CARD

        if (scannedDesktopUrl != null) {
            PairingCard(
                url = scannedDesktopUrl,
                state = signalingState,
                onConnect = { onConnect(scannedDesktopUrl) },
                onRetry = { onRetry(scannedDesktopUrl) }
            )
        } else {
            InfoCard(
                title = "Connect to Windows Studio",
                description = "Open MAHANAIM CAM on your desktop and scan its QR code to pair your phone."
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = streamStatus,
            color = CamMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "MAHANAIM CAM  •  YOUR PHONE. YOUR CAMERA. YOUR CHURCH.",
            color = CamMuted,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 0.6.sp
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// --------------------------------------------------
// LIVE CAMERA STUDIO
// --------------------------------------------------

@Composable
private fun LiveCameraStudio(
    manager: WebRTCManager?,
    streamStatus: String,
    sessionId: String,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // HEADER

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "MAHANAIM CAM",
                    color = CamText,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    letterSpacing = 0.8.sp
                )

                Text(
                    text = "LIVE CAMERA STUDIO",
                    color = CamGold,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )
            }

            Surface(
                color = Color(0x332BD98A),
                shape = RoundedCornerShape(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(CamGreen)
                    )

                    Spacer(modifier = Modifier.width(7.dp))

                    Text(
                        text = "CONNECTED",
                        color = CamGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // WEBRTC CAMERA PREVIEW

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black)
        ) {
            if (manager != null) {
                WebRtcLocalPreview(
                    manager = manager,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = CamGold
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = Color(0xCC111111),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(CamRed)
                    )

                    Spacer(modifier = Modifier.width(7.dp))

                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                color = Color(0xCC111111),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = streamStatus,
                    color = CamText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(9.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // STREAM INFORMATION

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "STREAM",
                value = "WEBRTC",
                modifier = Modifier.weight(1f)
            )

            MetricCard(
                title = "TARGET",
                value = "720p / 30",
                modifier = Modifier.weight(1f)
            )

            MetricCard(
                title = "BITRATE",
                value = "3 Mbps",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // CAMERA CONTROLS

        Text(
            text = "CAMERA CONTROLS",
            color = CamGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        var selectedQuality by remember { mutableStateOf("720p") }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = CamSurface,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CAMERA SOURCE",
                    color = CamMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = CamSurfaceLight,
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (manager?.isFrontCamera == true) "FRONT CAMERA" else "REAR CAMERA",
                                color = CamText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Currently streaming",
                                color = CamMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Button(
                        onClick = { manager?.switchCamera() },
                        enabled = manager != null,
                        modifier = Modifier.size(width = 112.dp, height = 58.dp),
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CamTeal,
                            contentColor = CamText,
                            disabledContainerColor = CamSurfaceLight
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⇄", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("FLIP", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = CamSurfaceLight)
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "STREAM QUALITY",
                    color = CamMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(9.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("480p", "720p", "1080p").forEach { quality ->
                        FilterChip(
                            selected = selectedQuality == quality,
                            onClick = {
                                val format = when (quality) {
                                    "480p" -> Triple(854, 480, 30)
                                    "1080p" -> Triple(1920, 1080, 30)
                                    else -> Triple(1280, 720, 30)
                                }
                                if (manager?.setCaptureFormat(format.first, format.second, format.third) == true) {
                                    selectedQuality = quality
                                }
                            },
                            label = { Text(quality, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CamTeal,
                                selectedLabelColor = CamText,
                                containerColor = CamSurfaceLight,
                                labelColor = CamMuted
                            )
                        )
                    }
                }

                Text(
                    text = "30 FPS • Quality changes apply to the live camera stream. Higher resolutions require more network bandwidth and phone processing power.",
                    color = CamMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = CamSurfaceLight)
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ORIENTATION",
                    color = CamMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(9.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CamSurfaceLight)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⟳", color = CamGold, fontSize = 23.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Automatic rotation", color = CamText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Rotate your phone to switch portrait / landscape framing.", color = CamMuted, fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "More hardware controls such as zoom, torch, focus and exposure will be enabled when the camera-control layer is integrated with your phone's Camera2 hardware.",
                    color = CamMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SESSION INFORMATION


        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = CamSurface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(15.dp)
            ) {
                Text(
                    text = "DESKTOP SESSION",
                    color = CamGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = sessionId.take(16),
                    color = CamText,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // DISCONNECT

        Button(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF47303A)
            )
        ) {
            Text(
                text = "DISCONNECT STREAM",
                color = Color(0xFFFFA6AE),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// --------------------------------------------------
// WEBRTC LOCAL PREVIEW
// --------------------------------------------------

@Composable
private fun WebRtcLocalPreview(
    manager: WebRTCManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val renderer = remember(context) {
        SurfaceViewRenderer(context)
    }

    DisposableEffect(manager, renderer) {
        val attached = manager.attachLocalPreviewRenderer(renderer)

        Log.d(
            TAG,
            "Local WebRTC preview attached: $attached"
        )

        onDispose {
            manager.detachLocalPreviewRenderer()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { renderer }
    )
}

// --------------------------------------------------
// BRAND HEADER
// --------------------------------------------------

@Composable
private fun BrandHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {

            Text(
                text = "MAHANAIM CAM",
                color = CamText,
                fontSize = 25.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "CHURCH WEBCAM",
            color = CamGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your Phone. Your Camera. Your Church.",
            color = CamMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
// --------------------------------------------------
// CONNECTION STATUS
// --------------------------------------------------

@Composable
private fun ConnectionStatusCard(
    signalingState: SignalingState
) {
    val connected = signalingState is SignalingState.Paired

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CamSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) CamGreen else CamGold
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = when (signalingState) {
                        is SignalingState.Disconnected -> "Not connected"
                        is SignalingState.Connecting -> "Connecting"
                        is SignalingState.AwaitingApproval -> "Waiting for approval"
                        is SignalingState.Paired -> "Connected"
                        is SignalingState.Rejected -> "Pairing rejected"
                        is SignalingState.Failed -> "Connection failed"
                    },
                    color = CamText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Text(
                    text = when (signalingState) {
                        is SignalingState.Disconnected ->
                            "Scan the desktop QR code to begin"

                        is SignalingState.Connecting ->
                            "Connecting to desktop..."

                        is SignalingState.AwaitingApproval ->
                            "Waiting for desktop pairing response"

                        is SignalingState.Paired ->
                            "Desktop pairing successful"

                        is SignalingState.Rejected ->
                            signalingState.reason

                        is SignalingState.Failed ->
                            signalingState.reason
                    },
                    color = CamMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// --------------------------------------------------
// PAIRING CARD
// --------------------------------------------------

@Composable
private fun PairingCard(
    url: String,
    state: SignalingState,
    onConnect: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CamSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "DESKTOP FOUND",
                color = CamGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = url,
                color = CamText,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is SignalingState.Disconnected -> {
                    StudioActionButton(
                        text = "Connect to Desktop",
                        modifier = Modifier.fillMaxWidth(),
                        highlighted = true,
                        onClick = onConnect
                    )
                }

                is SignalingState.Connecting -> {
                    ConnectionProgress("Connecting...")
                }

                is SignalingState.AwaitingApproval -> {
                    ConnectionProgress("Waiting for desktop approval...")
                }

                is SignalingState.Rejected -> {
                    Text(
                        text = state.reason,
                        color = CamRed,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    StudioActionButton(
                        text = "Try Again",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry
                    )
                }

                is SignalingState.Failed -> {
                    Text(
                        text = state.reason,
                        color = CamRed,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    StudioActionButton(
                        text = "Retry Connection",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry
                    )
                }

                is SignalingState.Paired -> {
                    Text(
                        text = "Pairing successful",
                        color = CamGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionProgress(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = CamGold,
            strokeWidth = 2.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = text,
            color = CamMuted,
            fontSize = 12.sp
        )
    }
}

// --------------------------------------------------
// ACTION BUTTON
// --------------------------------------------------

@Composable
private fun StudioActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) CamTeal else CamSurfaceLight,
            contentColor = CamText,
            disabledContainerColor = CamSurface,
            disabledContentColor = CamMuted
        )
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

// --------------------------------------------------
// METRIC CARD
// --------------------------------------------------

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = CamSurface,
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(
            modifier = Modifier.padding(11.dp)
        ) {
            Text(
                text = title,
                color = CamMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = value,
                color = CamGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// --------------------------------------------------
// INFORMATION CARD
// --------------------------------------------------

@Composable
private fun InfoCard(
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CamSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                color = CamGold,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                color = CamMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// --------------------------------------------------
// CAMERA PREVIEW BEFORE STREAMING
// --------------------------------------------------

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false
        var cameraBound = false

        val executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            if (disposed) return@addListener

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )

                cameraBound = true

                Log.d(TAG, "Connection camera preview started")
            } catch (exception: Exception) {
                Log.e(TAG, "Camera preview failed", exception)
            }
        }, executor)

        onDispose {
            disposed = true

            if (cameraBound) {
                cameraProvider?.unbindAll()
                Log.d(TAG, "Connection camera preview stopped")
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}
