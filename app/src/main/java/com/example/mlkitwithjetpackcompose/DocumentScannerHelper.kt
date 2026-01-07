package com.example.mlkitwithjetpackcompose

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class DocumentScannerHelper(
    private val activity: Activity,
    private val onResult: (List<Uri>, String?) -> Unit,
    private val onError: (String) -> Unit,
    private val pageLimit: Int = 1,
    private val isGalleryImportAllowed: Boolean = true,
    private val additionalText: String? = null,
    private val isLocationRequired: Boolean = false
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(isGalleryImportAllowed)
        .setPageLimit(pageLimit)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG/*, GmsDocumentScannerOptions.RESULT_FORMAT_PDF*/)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
        .build()

    private val scanner = GmsDocumentScanning.getClient(options)

    /**
     * Start the scanning process
     */
    fun startScan(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e -> onError(e.message ?: "Scanner failed to start") }
    }

    /**
     * Call this inside your activity/fragment result callback
     */
    fun handleResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
            scanningResult?.let { processScanningResult(it) }
        }
    }

    private fun processScanningResult(result: GmsDocumentScanningResult) {
        scope.launch {
            val locationInfo = fetchLocationAndAddress(activity)

            // 1. Process Images with Text Overlay
            val processedUris = result.pages?.mapNotNull { page ->
                try {
                    val originalBitmap = MediaStore.Images.Media.getBitmap(activity.contentResolver, page.imageUri)
                    val modifiedBitmap = addTextToBitmap(originalBitmap, locationInfo)
                    saveBitmapAndGetUri(activity, modifiedBitmap)
                } catch (e: Exception) { null }
            } ?: emptyList()

            // 2. Process PDF
            var pdfPath: String? = null
            //for now its not required.
            /*result.pdf?.let { pdf ->
                val pdfFile = File(activity.getExternalFilesDir(""), "${UUID.randomUUID()}.pdf")
                try {
                    activity.contentResolver.openInputStream(pdf.uri)?.use { input ->
                        FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
                    }
                    pdfPath = pdfFile.absolutePath
                } catch (e: Exception) { }
            }*/

            onResult(processedUris, pdfPath)
        }
    }

    // --- REUSABLE LOGIC FROM ORIGINAL CODE ---

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocationAndAddress(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = fusedLocationClient.lastLocation.await() ?: return@withContext "Location not found"

            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }

            val addressText = if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else "No address"
            "Latitude: ${"%.4f".format(location.latitude)}, Longitude: ${"%.4f".format(location.longitude)}\nAddress: $addressText"
        } catch (e: Exception) { "Location unavailable" }
    }

    private fun addTextToBitmap(originalBitmap: Bitmap, text: String): Bitmap {
        val paint = TextPaint().apply {
            color = Color.WHITE
            textSize = 50f
            isAntiAlias = true
        }
        val padding = 40f
        val maxTextWidth = originalBitmap.width - (2 * padding)
        val textLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxTextWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val newHeight = originalBitmap.height + textLayout.height + (2 * padding.toInt())
        val newBitmap = createBitmap(originalBitmap.width, newHeight, originalBitmap.config!!)
        val canvas = Canvas(newBitmap)

        canvas.drawColor(Color.DKGRAY)
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)
        canvas.withTranslation(padding, originalBitmap.height + padding) {
            textLayout.draw(this)
        }
        return newBitmap
    }

    private suspend fun saveBitmapAndGetUri(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val tempFile = File(context.getExternalFilesDir(""), "${UUID.randomUUID()}.jpg")
        FileOutputStream(tempFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.provider", tempFile)
    }

    fun onDestroy() {
        scope.cancel()
    }
}