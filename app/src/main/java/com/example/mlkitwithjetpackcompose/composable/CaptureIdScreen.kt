package com.example.mlkitwithjetpackcompose.composable

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.data.IdType
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors


enum class CaptureStep { FRONT, BACK }

@Composable
fun CaptureIdScreen(
    requiredIdType: IdType, // Pass the type you want to scan (e.g., IdType.AADHAAR)
    onIdVerified: (ExtractedDocument) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- State Management ---
    var isProcessing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    // Multi-Step State
    // Determine if this ID needs two steps
    val isMultiStep = remember(requiredIdType) {
        requiredIdType == IdType.AADHAAR || requiredIdType == IdType.PASSPORT
    }
    var currentStep by remember { mutableStateOf(CaptureStep.FRONT) }
    var frontResult by remember { mutableStateOf<ExtractedDocument?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }

    // Flash Logic
    LaunchedEffect(isFlashOn) {
        imageCapture.flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
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
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                        )
                        cameraControl = camera.cameraControl
                    } catch (e: Exception) { Log.e("Camera", "Bind failed", e) }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Overlay & Instructions
        ScannerOverlay()

        // Instruction Text
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = if (isMultiStep && currentStep == CaptureStep.BACK) {
                "Scan BACK of ${requiredIdType.name}"
            } else {
                "Scan FRONT of ${requiredIdType.name}"
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            if (frontResult != null) {
                Text(
                    text = "✓ Front Captured",
                    color = Color.Green,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Flash Button
        IconButton(
            onClick = { isFlashOn = !isFlashOn },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 20.dp)
        ) {
            Icon(
                imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "Flash",
                tint = if (isFlashOn) Color.Yellow else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // 3. Capture Button & Logic
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isProcessing) {
                CircularProgressIndicator(color = Color.White)
                Text("Processing...", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            } else {
                FloatingActionButton(
                    onClick = {
                        isProcessing = true
                        captureAndProcess(
                            context = context,
                            imageCapture = imageCapture,
                            executor = cameraExecutor,
                            requiredType = requiredIdType,
                            isBackSide = currentStep == CaptureStep.BACK,
                            onResult = { result, uri->
                                isProcessing = false

                                val resultWithImage = when(result) {
                                    is ExtractedDocument.Aadhaar -> {
                                        when(currentStep) {
                                            CaptureStep.FRONT -> result.copy(frontImageUri = uri)
                                            CaptureStep.BACK -> result.copy(backImageUri = uri)
                                        }
                                    }
                                    is ExtractedDocument.Passport -> {
                                        when(currentStep) {
                                            CaptureStep.FRONT -> result.copy(frontImageUri = uri)
                                            CaptureStep.BACK -> result.copy(backImageUri = uri)
                                        }
                                    }
                                    is ExtractedDocument.Pan -> result.copy(imageUri = uri)
                                    is ExtractedDocument.DrivingLicense -> result.copy(imageUri = uri)
                                }

                                if (isMultiStep) {
                                    if (currentStep == CaptureStep.FRONT) {
                                        // Store front result and move to back
                                        frontResult = resultWithImage
                                        currentStep = CaptureStep.BACK
                                    } else {
                                        // Merge front + back and finish
                                        val mergedDoc = IdDataExtractor.mergeDetails(frontResult!!, resultWithImage)
                                        onIdVerified(mergedDoc)
                                    }
                                } else {
                                    // Single step (PAN/DL)
                                    onIdVerified(resultWithImage)
                                }
                            },
                            onError = { error ->
                                isProcessing = false
                                captureError = error
                            }
                        )
                    },
                    containerColor = Color.White,
                    modifier = Modifier.size(72.dp)
                ) { Icon(Icons.Default.Camera, contentDescription = "Capture") }
            }
        }

        // Error Dialog
        if (captureError != null) {
            AlertDialog(
                onDismissRequest = { captureError = null },
                confirmButton = { TextButton(onClick = { captureError = null }) { Text("Retry") } },
                title = { Text("Scan Failed") },
                text = { Text(captureError!!) }
            )
        }
    }
}

private fun captureAndProcess(
    context: Context,
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    requiredType: IdType,
    isBackSide: Boolean,
    onResult: (ExtractedDocument, String) -> Unit, // Return both doc and URI
    onError: (String) -> Unit
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
                        val bitmap = imageProxy.toBitmap() 
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        
                        val matrix = android.graphics.Matrix().apply {
                            postRotate(rotationDegrees.toFloat())
                        }

                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        
                        val fileName = "ID_${System.currentTimeMillis()}.jpg"
                        val file = File(context.filesDir, fileName)
                        try {
                            FileOutputStream(file).use { out ->
                                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        } catch (e: Exception) {
                            onError("Failed to save image")
                            return@addOnSuccessListener
                        }
                        val savedUri = file.absolutePath

                        Log.d("TAG", "visionText: ${visionText.text}")
                        // 1. Back Side Logic (Focus on Address)
                        if (isBackSide) {
                            val backResult = IdDataExtractor.extractBackSideData(visionText, requiredType)
                            if (backResult != null) {
                                onResult(backResult,savedUri)
                            } else {
                                onError("Could not detect Address on the back side. Please ensure text is clear.")
                            }
                        }
                        // 2. Front Side / Single Doc Logic (Full Extraction)
                        else {
                            val result = IdDataExtractor.extractData(visionText)

                            if (result == null) {
                                onError("No valid ID detected. Please try again.")
                            } else if (result.type != requiredType) {
                                onError("Incorrect ID Type detected. Expected ${requiredType.name} but found ${result.type.name}.")
                            } else {
                                onResult(result,savedUri)
                            }
                        }
                    }
                    .addOnFailureListener { onError(it.localizedMessage ?: "OCR Failed") }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
                onError("Image capture failed")
            }
        }
        override fun onError(e: ImageCaptureException) {
            onError("Camera Error: ${e.message}")
        }
    })
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