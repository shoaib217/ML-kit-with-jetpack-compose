package com.example.mlkitwithjetpackcompose.composable

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.mlkitwithjetpackcompose.utility.createImageFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions

@Composable
fun TextRecognitionScreen() {
    val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    val context = LocalContext.current

    var imageUri by rememberSaveable {
        mutableStateOf(Uri.EMPTY)
    }
    var extractedImageText by rememberSaveable {
        mutableStateOf("")
    }
    var showProgressDialog by remember { mutableStateOf(false) }
    val file = context.createImageFile()
    val uri = FileProvider.getUriForFile(
        context,
        "com.example.mlkitwithjetpackcompose" + ".provider", file
    )

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                showProgressDialog = true
                imageUri = uri
                recognizer.process(InputImage.fromFilePath(context, imageUri ?: Uri.EMPTY))
                    .addOnSuccessListener {
                        extractedImageText = it.text
                        showProgressDialog = false

                    }
                    .addOnFailureListener {
                        showProgressDialog = false
                        Log.d("TAG", "Exception: ${it.message}")
                    }
            }
        })


    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        if (showProgressDialog){
            CircularProgressIndicator()
        } else {
            if (extractedImageText.isNotEmpty()) {
                Column (modifier = Modifier.padding(8.dp)){
                    Text(text = "Recognized Text : - ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(text = extractedImageText)
                    }
                }
            }
            if (imageUri != null){
                AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(10.dp)
                )
            }
            Button(onClick = {
                launcher.launch(uri)
            }) {
                Text(text = "Capture Image")
            }
        }
    }
}