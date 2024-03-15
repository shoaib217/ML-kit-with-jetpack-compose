package com.example.mlkitwithjetpackcompose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mlkitwithjetpackcompose.composable.DocumentScannerScreen
import com.example.mlkitwithjetpackcompose.composable.TextRecognitionScreen
import com.example.mlkitwithjetpackcompose.ui.theme.MLkitWithJetpackComposeTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val TEXT_RECOGNITION_SCREEN = "textRecognitionScreen"
        const val DOCUMENT_SCANNER_SCREEN = "documentScannerScreen"
        const val MAIN_SCREEN = "mainScreen"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {

        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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


        setContent {
            MLkitWithJetpackComposeTheme {
                val navController = rememberNavController()
                Scaffold(topBar = {
                    TopAppBar(title = { Text(text = "ML Kit Demo") })
                }, containerColor = MaterialTheme.colorScheme.background) {
                    NavHost(
                        navController = navController,
                        startDestination = MAIN_SCREEN,
                        modifier = Modifier.fillMaxSize().padding(it)
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
    }
}
