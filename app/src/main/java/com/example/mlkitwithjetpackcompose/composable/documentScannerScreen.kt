package com.example.mlkitwithjetpackcompose.composable

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.mlkitwithjetpackcompose.MainActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

@Composable
fun DocumentScannerScreen(mainActivity: MainActivity) {
    val context = LocalContext.current
    var imageUri by remember {
        mutableStateOf<List<Uri>>((emptyList()))
    }

    val coroutineScope = rememberCoroutineScope()

    var lastPdfPath by remember { mutableStateOf<String?>(null) }

    val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false).setPageLimit(4)
        .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF).setScannerMode(SCANNER_MODE_FULL)
        .build()
    val scanner = GmsDocumentScanning.getClient(options)
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            // Improvement 1: Use 'when' to handle different result codes for better readability and scalability
            when (result.resultCode) {
                RESULT_OK -> {
                    GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.let { scanningResult ->
                        // The 'pages' property is a list of scanned pages
                        imageUri = scanningResult.pages?.map { it.imageUri } ?: emptyList()

                        scanningResult.pdf?.let { pdf ->
                            // Improvement 2: Perform file I/O on a background thread to avoid ANRs
                            coroutineScope.launch(Dispatchers.IO) {
                                val pdfFile = File(context.filesDir, "${UUID.randomUUID()}.pdf") // Improvement 6: Use a unique filename
                                try {
                                    context.contentResolver.openInputStream(pdf.uri)?.use { inputStream ->
                                        FileOutputStream(pdfFile).use { outputStream ->
                                            inputStream.copyTo(outputStream)
                                            // Improvement 4: Add logging for success
                                            Log.d("DocumentScanner", "PDF saved successfully to ${pdfFile.absolutePath}")
                                        }
                                    }
//                                    lastPdfPath = pdfFile.absolutePath
                                } catch (e: IOException) {
                                    // Improvement 3 & 4: Handle exceptions and log errors
                                    Log.e("DocumentScanner", "Error saving PDF", e)
                                } catch (e: SecurityException) {
                                    Log.e("DocumentScanner", "Security error saving PDF", e)
                                }
                            }
                        }
                    }
                }
                RESULT_CANCELED -> {
                    // Handle user cancellation
                    Log.d("DocumentScanner", "Scan was cancelled by the user.")
                }
                else -> {
                    // Handle other potential results
                    Log.w("DocumentScanner", "Scan failed with result code: ${result.resultCode}")
                }
            }
        })



    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {

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
                // Use a chooser to let the user pick an app
                val chooser = Intent.createChooser(shareIntent, "Share PDF")
                context.startActivity(chooser)
            }) {
                Text("Share Last PDF")
            }
        }

        if (imageUri.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
            ) {
                items(imageUri) {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(10.dp)
                    )
                }
            }
        }
        Button(onClick = {
            scanner.getStartScanIntent(mainActivity).addOnSuccessListener {
                scannerLauncher.launch(IntentSenderRequest.Builder(it).build())
            }.addOnFailureListener {
                Toast.makeText(context,it.message, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(text = "Capture Image")
        }
    }

}