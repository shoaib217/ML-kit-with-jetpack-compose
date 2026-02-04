package com.example.mlkitwithjetpackcompose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mlkitwithjetpackcompose.composable.CaptureIdScreen
import com.example.mlkitwithjetpackcompose.composable.DocumentScannerScreen
import com.example.mlkitwithjetpackcompose.composable.TextRecognitionScreen
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.ui.theme.MLkitWithJetpackComposeTheme
import org.json.JSONObject
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    companion object {
        const val TEXT_RECOGNITION_SCREEN = "textRecognitionScreen"
        const val DOCUMENT_SCANNER_SCREEN = "documentScannerScreen"
        const val DOCUMENT_DETECTION = "document_detection"
        const val MAIN_SCREEN = "mainScreen"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {

        }

    private lateinit var scannerHelper: DocumentScannerHelper

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scannerHelper.handleResult(result.resultCode, result.data)
    }

    // 2. Register Permission Launcher
    private val permissionLauncherLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "Ready to scan with location", Toast.LENGTH_SHORT).show()
        }
    }


    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initLocal()) {
            Log.d("OpenCV", "OpenCV initialized successfully!")
        } else {
            Log.e("OpenCV", "Failed to initialize OpenCV.")
        }
        var permissions =
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.RECORD_AUDIO
            )
        }
        permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }.also { permissionToRequest ->
            if (permissionToRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionToRequest.toTypedArray())
            }
        }

        scannerHelper = DocumentScannerHelper(
            activity = this,
            onResult = { uris, pdfPath ->
                // Update your ImageView or RecyclerView with uris
                // Store pdfPath for sharing
                Toast.makeText(this, "Processed ${uris.size} images", Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
            }
        )
//        scannerHelper.startScan(scannerLauncher)

        // Request Permissions
        permissionLauncherLocation.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))


        setContent {
            MLkitWithJetpackComposeTheme {
                val navController = rememberNavController()
                Scaffold(topBar = {
                    TopAppBar(title = { Text(text = "ML Kit Demo") })
                }, containerColor = MaterialTheme.colorScheme.background) {
                    NavHost(
                        navController = navController,
                        startDestination = MAIN_SCREEN,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(it)
                    ) {
                        composable(MAIN_SCREEN) {
                            MainScreen(navController)
                        }
                        composable(TEXT_RECOGNITION_SCREEN) {
                            TextRecognitionScreen()

                        }
                        composable(DOCUMENT_SCANNER_SCREEN) {
                            DocumentScannerScreen(mainActivity = this@MainActivity)
                        }
                        composable(DOCUMENT_DETECTION) {
                            var extractedDocument by remember { mutableStateOf<ExtractedDocument?>(null) }
                            CaptureIdScreen(onIdVerified = { result ->
                                println("result - $result")
                                val ocrJSON = JSONObject().apply {
                                    put("type", result.type.name)
                                    put("idNumber", result.idNumber)
                                    put("name", result.name)
                                    put("dob", result.dob)
                                    put("address", result.address)
                                    put("isExpired", result.isExpired)
                                }
                                println("ocrJSON - $ocrJSON")
                                extractedDocument = result


                            })
                            if (extractedDocument != null) {
                                val extractedData = "Document Type: ${extractedDocument?.type?.name} \n" +
                                        "ID Number: ${extractedDocument?.idNumber} \n" +
                                        "Name: ${extractedDocument?.name} \n" +
                                        "DOB: ${extractedDocument?.dob} \n" +
                                        "Address: ${extractedDocument?.address} \n" +
                                        "Is Expired: ${if (extractedDocument?.isExpired == true) "Yes" else "No"}"
                                AlertDialog(
                                    onDismissRequest = { extractedDocument = null },
                                    confirmButton = {
                                        TextButton(onClick = { extractedDocument = null }) { Text("OK") }
                                    },
                                    title = { Text("Extracted Document") },
                                    text = { Text(extractedData) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(navController: NavHostController) {
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(onClick = { navController.navigate(MainActivity.TEXT_RECOGNITION_SCREEN) }) {
            Text(text = "Go to Text Recognition Screen")
        }
        FilledTonalButton(onClick = { navController.navigate(MainActivity.DOCUMENT_SCANNER_SCREEN) }) {
            Text(text = "Go to Document Scanner Screen")
        }
        FilledTonalButton(onClick = { navController.navigate(MainActivity.DOCUMENT_DETECTION) }) {
            Text(text = "Go to Document verification")
        }
    }
}
