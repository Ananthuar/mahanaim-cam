
package com.mahanaim.cam.ui.screens

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MahanaimCAM/QR"

@Composable
fun QrScannerPreview(
    onQrCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mainExecutor = remember(context) {
        ContextCompat.getMainExecutor(context)
    }

    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        BarcodeScanning.getClient(options)
    }

    val hasScanned = remember {
        AtomicBoolean(false)
    }

    val currentCallback by rememberUpdatedState(onQrCodeScanned)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView, scanner) {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        cameraProviderFuture.addListener({
            if (disposed) return@addListener

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeImage(
                        imageProxy = imageProxy,
                        scanner = scanner,
                        onQrCodeScanned = { url ->
                            mainExecutor.execute {
                                if (hasScanned.compareAndSet(false, true)) {
                                    currentCallback(url)
                                }
                            }
                        }
                    )
                }

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                Log.d(TAG, "QR scanner camera started")

            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start QR scanner", exception)
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
            scanner.close()
            cameraExecutor.shutdown()

            Log.d(TAG, "QR scanner stopped")
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SCAN DESKTOP QR CODE",
                color = Color.White,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Position the QR code inside the camera view",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }
    }
}

private fun analyzeImage(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onQrCodeScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image

    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(
        mediaImage,
        imageProxy.imageInfo.rotationDegrees
    )

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                val rawValue = barcode.rawValue ?: continue

                try {
                    val payload = JSONObject(rawValue)

                    val type = payload.optString("type")
                    val version = payload.optInt("version")
                    val url = payload.optString("url")

                    if (
                        type == "MAHANAIM_CAM_PAIR" &&
                        version == 1 &&
                        url.startsWith("ws://") &&
                        url.length <= 256
                    ) {
                        onQrCodeScanned(url)
                        break
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "Invalid QR payload", exception)
                }
            }
        }
        .addOnFailureListener { exception ->
            Log.e(TAG, "QR scanning failed", exception)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
