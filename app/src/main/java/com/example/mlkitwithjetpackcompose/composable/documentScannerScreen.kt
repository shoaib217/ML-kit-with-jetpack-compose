package com.example.mlkitwithjetpackcompose.composable

import android.app.Activity.RESULT_OK
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.mlkitwithjetpackcompose.MainActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream

@Composable
fun DocumentScannerScreen(mainActivity: MainActivity) {
    val context = LocalContext.current
    var imageUri by remember {
        mutableStateOf<List<Uri>>((emptyList()))
    }
    val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false).setPageLimit(4)
        .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF).setScannerMode(SCANNER_MODE_FULL)
        .build()
    val scanner = GmsDocumentScanning.getClient(options)
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { res ->
            if (res.resultCode == RESULT_OK) {
                GmsDocumentScanningResult.fromActivityResultIntent(res.data).let { result ->
                    result?.pages?.let { pages ->
                        imageUri = pages.map { it.imageUri }.toList()
                    }

                    result?.pdf?.let { pdf ->
                        val fos = FileOutputStream(File(context.filesDir, "scan.pdf"))
                        context.contentResolver.openInputStream(pdf.uri)?.use { inputStream ->
                            inputStream.copyTo(fos)
                        }
                    }
                }
            }
        })


    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
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