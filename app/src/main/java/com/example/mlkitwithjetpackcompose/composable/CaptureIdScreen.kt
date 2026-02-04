package com.example.mlkitwithjetpackcompose.composable

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

@Composable
fun CaptureIdScreen(onIdVerified: (ExtractedDocument) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // UI States
    var isProcessing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isFlashOn by remember { mutableStateOf(false) } // New state for Flash

    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

   // Camera References
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                    )
                    .build()
            )
            .build()
    }

    LaunchedEffect(isFlashOn) {
        imageCapture.flashMode = if (isFlashOn) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        cameraControl?.enableTorch(isFlashOn)
    }

    val executor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture // Bind Capture instead of Analysis
                        )

                        cameraControl = camera.cameraControl
                    } catch (e: Exception) {
                        Log.e("Camera", "Bind failed", e)
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Overlay Guide (Hole in the screen)
        ScannerOverlay()

        IconButton(
            onClick = { isFlashOn = !isFlashOn },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 20.dp)
        ) {
            Icon(
                imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "Toggle Flash",
                tint = if (isFlashOn) Color.Yellow else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // 3. Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isProcessing) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Validating Document...", color = Color.White)
            } else {
                FloatingActionButton(
                    onClick = {
                        isProcessing = true
                        captureAndValidate(
                            imageCapture, 
                            cameraExecutor, 
                            onSuccess = { result ->
                                isProcessing = false
                                onIdVerified(result)
                            },
                            onError = { error ->
                                isProcessing = false
                                captureError = error
                            }
                        )
                    },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = "Camera")
                }
            }
        }

        // 4. Error Feedback Dialog
        if (captureError != null) {
            AlertDialog(
                onDismissRequest = { captureError = null },
                confirmButton = {
                    TextButton(onClick = { captureError = null }) { Text("Retry") }
                },
                title = { Text("Validation Failed") },
                text = { Text(captureError!!) }
            )
        }
    }
}

// Logic to Capture -> Process -> Validate
private fun captureAndValidate(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    onSuccess: (ExtractedDocument) -> Unit,
    onError: (String) -> Unit,
) {
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        println("visionText : ${visionText.text}")
                        val result = IdDataExtractor.extractData(visionText)
                        if (result != null) {
                            onSuccess(result)
                        } else {
                            onError("Could not verify ID. \n\nEnsure no glare, good lighting, and valid Indian ID.")
                        }
                    }
                    .addOnFailureListener { e ->
                        onError("Text recognition failed: ${e.localizedMessage}")
                    }
                    .addOnCompleteListener {
                        imageProxy.close() // CRITICAL: Close to free memory
                    }
            } else {
                imageProxy.close()
                onError("Capture failed.")
            }
        }

        override fun onError(exception: ImageCaptureException) {
            onError("Camera error: ${exception.localizedMessage}")
        }
    })
}

@Composable
fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        
        // Draw semi-transparent background
        drawRect(color = Color.Black.copy(alpha = 0.6f))

        // Create the "Clear" hole
        val holeWidth = canvasWidth * 0.9f
        val holeHeight = holeWidth * 0.63f // Aspect ratio of ID card
        val holeLeft = (canvasWidth - holeWidth) / 2
        val holeTop = (canvasHeight - holeHeight) / 2

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(holeLeft, holeTop),
            size = Size(holeWidth, holeHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        // Draw white border around hole
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(holeLeft, holeTop),
            size = Size(holeWidth, holeHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}