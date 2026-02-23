package com.example.mlkitwithjetpackcompose.composable

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.data.IdType
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

@Composable
fun DocumentScannerScreenAndOCR(
    requiredIdType: IdType,
    onIdVerified: (ExtractedDocument) -> Unit,
    onCancel: () -> Unit // Added so parent can close the camera screen if user cancels
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var isProcessing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var hasLaunchedScanner by remember { mutableStateOf(false) }

    val isMultiStep = remember(requiredIdType) {
        requiredIdType == IdType.AADHAAR || requiredIdType == IdType.PASSPORT
    }

    // 1. Google Document Scanner Launcher
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages

            if (!pages.isNullOrEmpty()) {
                isProcessing = true
                processScanResults(
                    context = context,
                    pages = pages,
                    requiredType = requiredIdType,
                    onSuccess = { extractedDoc ->
                        isProcessing = false
                        onIdVerified(extractedDoc)
                    },
                    onError = { error ->
                        isProcessing = false
                        captureError = error
                    }
                )
            } else {
                onCancel()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            onCancel() // User backed out of the scanner
        }
    }

    // 2. Selfie Fallback Launcher
    // (Doc Scanner is bad for selfies, standard camera intent is better)
    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            isProcessing = true
            val uri = saveBitmapToInternalStorage(context, bitmap)
            isProcessing = false
            onIdVerified(ExtractedDocument.Selfie(imageUri = uri))
        } else {
            onCancel()
        }
    }

    // 3. Launch the Scanner automatically when the Composable enters the screen
    LaunchedEffect(Unit) {
        if (!hasLaunchedScanner) {
            hasLaunchedScanner = true

            if (requiredIdType == IdType.SELFIE) {
                selfieLauncher.launch(null)
            } else {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(if (isMultiStep) 2 else 1) // Allow 2 pages for Aadhaar/Passport
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()

                val scanner = GmsDocumentScanning.getClient(options)

                scanner.getStartScanIntent(activity!!)
                    .addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
                    .addOnFailureListener { e ->
                        captureError = e.localizedMessage ?: "Failed to launch document scanner"
                    }
            }
        }
    }

    // 4. Loading & Error UI (Shown while OCR is running or if scanning failed)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isProcessing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Extracting Data...", modifier = Modifier.padding(top = 16.dp))
            }
        }

        if (captureError != null) {
            AlertDialog(
                onDismissRequest = { captureError = null },
                title = { Text("Scan Failed") },
                text = { Text(captureError!!) },
                confirmButton = {
                    TextButton(onClick = {
                        captureError = null
                        hasLaunchedScanner = false // Reset to allow re-launch
                    }) { Text("Retry") }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            )
        }
    }
}

/**
 * Shared ML Kit logic to process the pristine JPEGs returned by Google Document Scanner
 */
private fun processScanResults(
    context: Context,
    pages: List<GmsDocumentScanningResult.Page>,
    requiredType: IdType,
    onSuccess: (ExtractedDocument) -> Unit,
    onError: (String) -> Unit
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val isMultiStep = requiredType == IdType.AADHAAR || requiredType == IdType.PASSPORT

    try {
        // Process Front Page (Index 0)
        val frontUri = pages[0].imageUri
        val frontImage = InputImage.fromFilePath(context, frontUri)

        recognizer.process(frontImage)
            .addOnSuccessListener { frontVisionText ->
                Log.d("TAG", "frontVisionText: ${frontVisionText.text}")

                val frontResult = IdDataExtractor.extractData(frontVisionText)

                if (frontResult == null || frontResult.type != requiredType) {
                    onError("Invalid ID or Type Mismatch on Front Page. Expected ${requiredType.name}.")
                    return@addOnSuccessListener
                }
                val savedFrontUri = try {
                    saveUriToInternalStorage(context, frontUri)
                } catch (e: Exception) {
                    onError("Failed to save front image")
                    return@addOnSuccessListener
                }

                val frontDocWithUri = attachUriToDoc(frontResult, savedFrontUri, true)

                // If Multi-step, Process Back Page (Index 1)
                if (isMultiStep) {
                    if (pages.size < 2) {
                        onError("Please scan BOTH front and back sides of the ${requiredType.name}.")
                        return@addOnSuccessListener
                    }

                    val backUri = pages[1].imageUri
                    val backImage = InputImage.fromFilePath(context, backUri)

                    recognizer.process(backImage)
                        .addOnSuccessListener { backVisionText ->
                            Log.d("TAG", "backVisionText: ${backVisionText.text}")

                            val backResult = IdDataExtractor.extractBackSideData(backVisionText, requiredType)
                            if (backResult == null) {
                                onError("Could not read back side data. Please ensure it is clear.")
                                return@addOnSuccessListener
                            }

                            val savedBackUri = try {
                                saveUriToInternalStorage(context, backUri)
                            } catch (e: Exception) {
                                onError("Failed to save back image")
                                return@addOnSuccessListener
                            }

                            val backDocWithUri = attachUriToDoc(backResult, savedBackUri, false)

                            // Merge the Front and Back results
                            val mergedDoc = IdDataExtractor.mergeDetails(frontDocWithUri, backDocWithUri)
                            onSuccess(mergedDoc)
                        }
                        .addOnFailureListener { onError(it.localizedMessage ?: "OCR Failed on Back Page") }
                } else {
                    // Single page document success
                    onSuccess(frontDocWithUri)
                }
            }
            .addOnFailureListener { onError(it.localizedMessage ?: "OCR Failed on Front Page") }

    } catch (e: Exception) {
        onError("Failed to process images: ${e.localizedMessage}")
    }
}

private fun attachUriToDoc(doc: ExtractedDocument, uri: String, isFront: Boolean): ExtractedDocument {
    return when (doc) {
        is ExtractedDocument.Aadhaar -> if (isFront) doc.copy(frontImageUri = uri) else doc.copy(backImageUri = uri)
        is ExtractedDocument.Passport -> if (isFront) doc.copy(frontImageUri = uri) else doc.copy(backImageUri = uri)
        is ExtractedDocument.Pan -> doc.copy(imageUri = uri)
        is ExtractedDocument.DrivingLicense -> doc.copy(imageUri = uri)
        is ExtractedDocument.Selfie -> doc.copy(imageUri = uri)
    }
}


private fun saveUriToInternalStorage(context: Context, uri: Uri): String {
    val fileName = "ID_${System.currentTimeMillis()}.jpg"
    val file = File(context.filesDir, fileName)

    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(file).use { output ->
            input.copyTo(output)
        }
    } ?: throw Exception("Could not open URI stream")

    return file.absolutePath
}

private fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap): String {
    val fileName = "SELFIE_${System.currentTimeMillis()}.jpg"
    val file = File(context.filesDir, fileName)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    return file.absolutePath
}
