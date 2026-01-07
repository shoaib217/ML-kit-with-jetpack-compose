package com.example.mlkitwithjetpackcompose.composable

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import coil.compose.AsyncImage
import com.example.mlkitwithjetpackcompose.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

@Composable
fun DocumentScannerScreen(mainActivity: MainActivity) {
    val context = LocalContext.current
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    var locationInfo by remember { mutableStateOf("Fetching location...") }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
            ) {
                // Permission granted, now fetch location
                coroutineScope.launch {
                    locationInfo = fetchLocationAndAddress(context, fusedLocationClient)
                }
            } else {
                locationInfo = "Location permission denied."
                Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // Trigger permission request when the screen is first composed
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    var lastPdfPath by remember { mutableStateOf<String?>(null) }

    val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false).setPageLimit(2)
        .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE).build()

    val scanner = GmsDocumentScanning.getClient(options)
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.let { scanningResult ->
                        coroutineScope.launch {
                            // Process images to add text
                            val processedImageUris = scanningResult.pages?.mapNotNull { page ->
                                try {
                                    // Get original bitmap from URI
                                    val originalBitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, page.imageUri)
                                    // Add text overlay
                                    val modifiedBitmap = addTextToBitmap(originalBitmap, locationInfo)
                                    // Save the new bitmap and get its URI
                                    saveBitmapAndGetUri(context, modifiedBitmap)
                                } catch (e: Exception) {
                                    Log.e("ImageProcessing", "Failed to process image", e)
                                    null // Return null if processing fails for one image
                                }
                            } ?: emptyList()

                            // Update the UI on the main thread
                            withContext(Dispatchers.Main) {
                                imageUris = processedImageUris
                            }

                            // Handle PDF saving in parallel
                            scanningResult.pdf?.let { pdf ->
                                val pdfFile = File(context.filesDir, "${UUID.randomUUID()}.pdf")
                                try {
                                    context.contentResolver.openInputStream(pdf.uri)?.use { inputStream ->
                                        FileOutputStream(pdfFile).use { outputStream ->
                                            inputStream.copyTo(outputStream)
                                            Log.d("DocumentScanner", "PDF saved successfully to ${pdfFile.absolutePath}")
                                            // Update path on the main thread for the share button
                                            withContext(Dispatchers.Main) {
                                                lastPdfPath = pdfFile.absolutePath
                                            }
                                        }
                                    }
                                } catch (e: IOException) {
                                    Log.e("DocumentScanner", "Error saving PDF", e)
                                } catch (e: SecurityException) {
                                    Log.e("DocumentScanner", "Security error saving PDF", e)
                                }
                            }
                        }
                    }
                }
                RESULT_CANCELED -> {
                    Log.d("DocumentScanner", "Scan was cancelled by the user.")
                }
                else -> {
                    Log.w("DocumentScanner", "Scan failed with result code: ${result.resultCode}")
                }
            }
        })

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {

        if (imageUris.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
            ) {
                items(imageUris) { uri ->
                    ZoomableAsyncImage(
                        model = uri,
                        contentDescription = "Scanned image with location",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(10.dp)
                    )
                }
            }
        }

        lastPdfPath?.let { path ->
            Button(onClick = {
                val pdfFile = File(path)
                val authority = "${context.packageName}.provider"
                val contentUri = FileProvider.getUriForFile(context, authority, pdfFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share PDF")
                context.startActivity(chooser)
            }) {
                Text("Share Last PDF")
            }
        }

        Button(onClick = {
            scanner.getStartScanIntent(mainActivity).addOnSuccessListener {
                scannerLauncher.launch(IntentSenderRequest.Builder(it).build())
            }.addOnFailureListener {
                Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(text = "Capture Image")
        }
    }
}

@Composable
fun ZoomableAsyncImage(
    modifier: Modifier = Modifier,
    model: Any?,
    contentDescription: String?,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = modifier) {
        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale *= zoomChange
            // Constrain the scale to a reasonable range
            scale = scale.coerceIn(1f, 5f)

            // Calculate the new offset
            val newOffset = offset + offsetChange

            // Calculate the boundaries to prevent panning outside the image
            val maxOffset = (this.constraints.maxWidth * (scale - 1)) / 2f
            val maxYOffset = (this.constraints.maxHeight * (scale - 1)) / 2f

            // Coerce the offset to stay within the image bounds
            offset = Offset(
                x = newOffset.x.coerceIn(-maxOffset, maxOffset),
                y = newOffset.y.coerceIn(-maxYOffset, maxYOffset)
            )
        }

        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                // Apply the zoom and pan transformations
                .graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
                )
                // Enable transformation gestures (zoom and pan)
                .transformable(state = state)
        )
    }
}

// --- HELPER FUNCTIONS ---

@SuppressLint("MissingPermission")
private suspend fun fetchLocationAndAddress(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient,
): String = withContext(Dispatchers.IO) {
    // 1. Check if Geocoder backend is present on the device
    if (!Geocoder.isPresent()) {
        Log.e("LocationFetch", "Geocoder service not available on this device.")
        return@withContext "Latitude: ${"%.4f".format(0.0)}, Longitude: ${"%.4f".format(0.0)}\nGeocoder not available."
    }

    try {
        val location = fusedLocationClient.lastLocation.await()
            ?: return@withContext "Last location not found."

        val geocoder = Geocoder(context, Locale.getDefault())
        var addressText = "No address found for this location."

        try {
            // 2. Use the correct Geocoder method based on Android version
            val addresses: List<Address>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // For Android 13 (API 33) and above, this is the modern way.
                    // Note: This API can be slow or fail if network is poor.
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                } else {
                    // For older versions, use the deprecated but necessary method.
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                }

            // 3. Check the result and construct the address string
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                // Build the address line by line for more robustness
                val fullAddress = address.getAddressLine(0)
                addressText = if (!fullAddress.isNullOrEmpty()) {
                    "Address: $fullAddress"
                } else {
                    "Address: Details not available."
                }
            }
        } catch (e: IOException) {
            // This catch block is important for network errors or geocoder issues
            Log.e("LocationFetch", "Failed to get address from Geocoder.", e)
            addressText = "Could not fetch address (network issue)."
        }

        return@withContext "Latitude: ${"%.4f".format(location.latitude)}, Longitude: ${"%.4f".format(location.longitude)}\n$addressText"

    } catch (e: Exception) {
        // Catches errors from fusedLocationClient.lastLocation or other exceptions
        Log.e("LocationFetch", "Error fetching location or address", e)
        return@withContext "Failed to get location."
    }
}

private fun addTextToBitmap(originalBitmap: Bitmap, text: String): Bitmap {
    // 1. Setup the paint for the text
    val paint = TextPaint().apply {
        color = Color.WHITE // Changed to white text
        textSize = 50f      // A reasonable text size
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    // --- MODIFICATION START ---

    val padding = 40f
    // Max text width is the image width minus padding on both sides
    val maxTextWidth = originalBitmap.width - (2 * padding)

    // 2. Use StaticLayout to measure the required height for the wrapped text
    val textLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxTextWidth.toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.0f)
        .setIncludePad(true)
        .build()

    // 3. Calculate the new bitmap's total height
    val textHeight = textLayout.height
    // New height = original height + space for text + top and bottom padding for the text area
    val newHeight = originalBitmap.height + textHeight + (2 * padding.toInt())

    // 4. Create a new, taller bitmap and fill it with a dark gray background
    val newBitmap = createBitmap(originalBitmap.width, newHeight, originalBitmap.config!!)
    val canvas = Canvas(newBitmap)
    canvas.drawColor(Color.DKGRAY) // Changed to dark gray background

    // 5. Draw the original scanned image at the top (coordinates 0,0)
    canvas.drawBitmap(originalBitmap, 0f, 0f, null)

    // 6. Draw the text in the new space at the bottom
    // Save the current canvas state before moving it
    canvas.withTranslation(padding, originalBitmap.height + padding) {
        // Position the text block: move it down below the original image and add left padding
        textLayout.draw(this)
        // Restore the canvas to its original state
    }

    // --- MODIFICATION END ---

    return newBitmap
}


private suspend fun saveBitmapAndGetUri(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
    FileOutputStream(tempFile).use {
        // Increased quality to 100 for better text readability
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
    }
    val authority = "${context.packageName}.provider"
    FileProvider.getUriForFile(context, authority, tempFile)
}