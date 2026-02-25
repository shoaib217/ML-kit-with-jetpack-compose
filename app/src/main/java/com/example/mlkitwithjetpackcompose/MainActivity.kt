package com.example.mlkitwithjetpackcompose

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.mlkitwithjetpackcompose.composable.CaptureIdScreen
import com.example.mlkitwithjetpackcompose.composable.DocumentScannerScreenAndOCR
import com.example.mlkitwithjetpackcompose.composable.TextRecognitionScreen
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.data.ExtractedDocumentData
import com.example.mlkitwithjetpackcompose.data.IdType
import com.example.mlkitwithjetpackcompose.ui.theme.MLkitWithJetpackComposeTheme
import com.example.mlkitwithjetpackcompose.utility.DocumentMatcher
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
        enableEdgeToEdge()
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
                var showExitDialog by remember { mutableStateOf(false) }

                // Exit Alert Dialog logic
                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Exit App") },
                        text = { Text("Are you sure you want to exit the application?") },
                        confirmButton = {
                            TextButton(onClick = { (this@MainActivity as Activity).finish() }) {
                                Text("Exit")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Handle back press on main screen
                BackHandler(enabled = true) {
                    showExitDialog = true
                }

                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(text = "ML Kit Demo") })
                    }, 
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing // Handle edge-to-edge for Scaffold
                ) { innerPadding ->
                    val listOfExtractedDocumentData = remember { mutableStateListOf<ExtractedDocumentData>() }
                    NavHost(
                        navController = navController,
                        startDestination = MAIN_SCREEN,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable(MAIN_SCREEN) {
                            MainScreen(navController,listOfExtractedDocumentData)
                        }
                        composable(TEXT_RECOGNITION_SCREEN) {
                            TextRecognitionScreen()

                        }
                        composable(DOCUMENT_SCANNER_SCREEN) {
//                            DocumentScannerScreen(mainActivity = this@MainActivity)
                            var expanded by remember { mutableStateOf(false) }
                            var selectedIdType by remember { mutableStateOf(IdType.AADHAAR) }
                            var showCamera by remember { mutableStateOf(false) }
                            var extractedDocument by remember { mutableStateOf<ExtractedDocument?>(null) }

                            if (!showCamera) {
                                // Selection UI
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Select ID Type to Verify",
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )

                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedIdType.name,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("ID Type") },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                            modifier = Modifier.menuAnchor()
                                        )

                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            IdType.entries.forEach { idType ->
                                                DropdownMenuItem(
                                                    text = { Text(idType.name) },
                                                    onClick = {
                                                        selectedIdType = idType
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { showCamera = true },
                                        modifier = Modifier.padding(top = 24.dp)
                                    ) {
                                        Text("Start Verification")
                                    }
                                }
                            } else {
                                DocumentScannerScreenAndOCR(
                                    requiredIdType = selectedIdType,
                                    onCancel = { showCamera = false }, // Close camera state if user presses back
                                    onIdVerified = { result ->
                                        showCamera = false // Hide camera view after success

                                        // 1. Create JSON using the safe sealed class properties
                                        val ocrJSON = JSONObject().apply {
                                            put("type", result.type.name)

                                            // Accessing common properties directly from the sealed class base/wrappers
                                            // (Note: Since we used a sealed class, we handle specific fields inside a when or via cast)
                                            when (result) {
                                                is ExtractedDocument.Aadhaar -> {
                                                    put("idNumber", result.id)
                                                    put("name", result.name)
                                                    put("dob", result.dob)
                                                    put("gender", result.gender?.name)
                                                    put("address", result.address)
                                                    put("isExpired", false)
                                                    listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.frontImageUri))
                                                }
                                                is ExtractedDocument.DrivingLicense -> {
                                                    put("idNumber", result.id)
                                                    put("name", result.name)
                                                    put("dob", result.dob)
                                                    put("address", result.address)
                                                    put("isExpired", result.isExpired)
                                                    put("gender", null)
                                                    listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.imageUri))

                                                }
                                                is ExtractedDocument.Pan -> {
                                                    put("idNumber", result.id)
                                                    put("name", result.name)
                                                    put("dob", result.dob)
                                                    put("address", null)
                                                    put("gender", null)
                                                    put("isExpired", false)
                                                    listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = null,imageUri = result.imageUri))

                                                }
                                                is ExtractedDocument.Passport -> {
                                                    put("idNumber", result.id)
                                                    put("name", result.name)
                                                    put("dob", result.dob)
                                                    put("gender", result.gender?.name)
                                                    put("isExpired", result.isExpired)
                                                    put("address", result.address)
                                                    listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.frontImageUri))

                                                }
                                                is ExtractedDocument.Selfie -> {
                                                    listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = null, dob = null, address = null,imageUri = result.imageUri) )
                                                }
                                            }
                                        }

                                        println("ocrJSON - $ocrJSON")
                                        extractedDocument = result
                                    }
                                )
                            }

                            // 2. Display Dialog with dynamic data based on the Sealed Class type
                            extractedDocument?.let { doc ->
                                AlertDialog(
                                    onDismissRequest = { extractedDocument = null },
                                    confirmButton = {
                                        TextButton(onClick = { extractedDocument = null }) { Text("OK") }
                                    },
                                    title = { Text("Extracted ${doc.type.name}") },
                                    text = {
                                         Text(buildDocumentDisplayString(doc))
                                    }
                                )
                            }
                        }
                        composable(DOCUMENT_DETECTION) {
                            var expanded by remember { mutableStateOf(false) }
                            var selectedIdType by remember { mutableStateOf(IdType.AADHAAR) }
                            var showCamera by remember { mutableStateOf(false) }
                            var extractedDocument by remember { mutableStateOf<ExtractedDocument?>(null) }

                            if (!showCamera) {
                                // 2. Selection UI
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Select ID Type to Verify",
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )

                                    // Material 3 Dropdown
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedIdType.name,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("ID Type") },
                                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                            modifier = Modifier.menuAnchor()
                                        )

                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            IdType.entries.forEach { idType ->
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text(idType.name) },
                                                    onClick = {
                                                        selectedIdType = idType
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { showCamera = true },
                                        modifier = Modifier.padding(top = 24.dp)
                                    ) {
                                        Text("Start Verification")
                                    }
                                }
                            } else {
                                CaptureIdScreen(requiredIdType = selectedIdType,onIdVerified = { result ->
                                    // 1. Create JSON using the safe sealed class properties
                                    val ocrJSON = JSONObject().apply {
                                        put("type", result.type.name)

                                        // Accessing common properties directly from the sealed class base/wrappers
                                        // (Note: Since we used a sealed class, we handle specific fields inside a when or via cast)
                                        when (result) {
                                            is ExtractedDocument.Aadhaar -> {
                                                put("idNumber", result.id)
                                                put("name", result.name)
                                                put("dob", result.dob)
                                                put("gender", result.gender?.name)
                                                put("address", result.address)
                                                put("isExpired", false)
                                                listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.frontImageUri))
                                            }
                                            is ExtractedDocument.DrivingLicense -> {
                                                put("idNumber", result.id)
                                                put("name", result.name)
                                                put("dob", result.dob)
                                                put("address", result.address)
                                                put("isExpired", result.isExpired)
                                                put("gender", null)
                                                listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.imageUri))

                                            }
                                            is ExtractedDocument.Pan -> {
                                                put("idNumber", result.id)
                                                put("name", result.name)
                                                put("dob", result.dob)
                                                put("address", null)
                                                put("gender", null)
                                                put("isExpired", false)
                                                listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = null,imageUri = result.imageUri))

                                            }
                                            is ExtractedDocument.Passport -> {
                                                put("idNumber", result.id)
                                                put("name", result.name)
                                                put("dob", result.dob)
                                                put("gender", result.gender?.name)
                                                put("isExpired", result.isExpired)
                                                put("address", result.address)
                                                listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = result.name, dob = result.dob, address = result.address,imageUri = result.frontImageUri))

                                            }
                                            is ExtractedDocument.Selfie -> {
                                                listOfExtractedDocumentData.add(ExtractedDocumentData(documentType = result.type,name = null, dob = null, address = null,imageUri = result.imageUri) )
                                            }
                                        }
                                    }

                                    println("ocrJSON - $ocrJSON")
                                    extractedDocument = result
                                })

                            }
                            // 2. Display Dialog with dynamic data based on the Sealed Class type
                            extractedDocument?.let { doc ->
                                AlertDialog(
                                    onDismissRequest = { extractedDocument = null },
                                    confirmButton = {
                                        TextButton(onClick = { extractedDocument = null }) { Text("OK") }
                                    },
                                    title = { Text("Extracted ${doc.type.name}") },
                                    text = {
                                        Text(buildDocumentDisplayString(doc))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildDocumentDisplayString(doc: ExtractedDocument): String {
    return buildString {
        // Common fields for all IDs
        val (id, name, dob) = when(doc) {
            is ExtractedDocument.Aadhaar -> Triple(doc.id, doc.name, doc.dob)
            is ExtractedDocument.DrivingLicense -> Triple(doc.id, doc.name, doc.dob)
            is ExtractedDocument.Pan -> Triple(doc.id, doc.name, doc.dob)
            is ExtractedDocument.Passport -> Triple(doc.id, doc.name, doc.dob)
            is ExtractedDocument.Selfie -> Triple(0, null, null)
        }

        append("ID Number: $id\n")
        name?.let { append("Name: $it\n") }
        dob?.let { append("DOB: $it\n") }

        // Specific fields
        when (doc) {
            is ExtractedDocument.Aadhaar -> {
                doc.address?.let { append("Address: $it\n") }
                doc.gender?.let { append("Gender: ${it.name}\n") }
            }
            is ExtractedDocument.Passport -> {
                doc.address?.let { append("Address: $it\n") }
                doc.gender?.let { append("Gender: ${it.name}\n") }
                doc.fatherName?.let { append("Father Name: ${it}\n") }
                doc.motherName?.let { append("Mother Name: ${it}\n") }
                doc.spouseName?.let { append("Spouse Name: ${it}\n") }
                append("Expired: ${if (doc.isExpired) "Yes" else "No"}")
            }
            is ExtractedDocument.DrivingLicense -> {
                doc.address?.let { append("Address: $it\n") }
                append("Expired: ${if (doc.isExpired) "Yes" else "No"}")
            }
            is ExtractedDocument.Pan -> {
                // No extra fields for PAN in this model
            }
            is ExtractedDocument.Selfie -> {

            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavHostController,
    listOfExtractedDocumentData: SnapshotStateList<ExtractedDocumentData>,
) {
    // Get the system insets to handle edge-to-edge properly in lists
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()

    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        val context = LocalContext.current

        // Track selected items by index
        var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
        var comparisonResult by remember { mutableStateOf<DocumentMatcher.MatchResult?>(null) }
        var isComparing by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Verified Documents (${selectedIndices.size}/2 selected)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )

            // 1. List View of Extracted Documents
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp + safeDrawingPadding.calculateStartPadding(layoutDirection),
                    end = 16.dp + safeDrawingPadding.calculateEndPadding(layoutDirection),
                    top = 16.dp,
                    bottom = 16.dp + safeDrawingPadding.calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(listOfExtractedDocumentData) { index, doc ->
                    val isSelected = selectedIndices.contains(index)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIndices = if (isSelected) {
                                    selectedIndices - index
                                } else if (selectedIndices.size < 2) {
                                    selectedIndices + index
                                } else {
                                    selectedIndices
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Document Image using Coil
                            AsyncImage(
                                model = doc.imageUri,
                                contentDescription = "Document Thumbnail",
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(text = doc.documentType.name, style = MaterialTheme.typography.titleMedium)
                                if (doc.documentType != IdType.SELFIE) {
                                    Text(text = doc.name ?: "Unknown Name", style = MaterialTheme.typography.titleMedium)
                                    Text(text = "DOB: ${doc.dob ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                                    // Assuming IdType is part of doc or can be inferred
                                    Text(
                                        text = "Pincode: ${doc.address ?: "N/A"}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Navigation & Compare Actions
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2 // Keeps it looking clean
            ){
                Button(onClick = { navController.navigate(MainActivity.DOCUMENT_DETECTION) }) {
                    Text(text = "Add New ID")
                }

                FilledTonalButton(onClick = { navController.navigate(MainActivity.DOCUMENT_SCANNER_SCREEN) }) {
                    Text(text = "Document Scanner")
                }

                // Show compare only when exactly 2 are selected
                AnimatedVisibility(selectedIndices.size == 2) {
                    Button(
                        onClick = {
                            val indices = selectedIndices.toList()
                            val doc1 = listOfExtractedDocumentData[indices[0]]
                            val doc2 = listOfExtractedDocumentData[indices[1]]

                            println("doc1 - $doc1")
                            println("doc2 - $doc2")
                            isComparing = true
                            DocumentMatcher.calculateTotalMatch(
                                doc1,
                                BitmapFactory.decodeFile(doc1.imageUri),
                                doc2,
                                BitmapFactory.decodeFile(doc2.imageUri),
                                context,
                                onComplete = { result ->
                                    comparisonResult = result
                                    isComparing = false
                                }
                            )
                        },
                        enabled = !isComparing
                    ) {
                        if (isComparing) CircularProgressIndicator()
                        else Text(text = "Compare Selection")
                    }
                }

            }
        }

        // 3. Match Result Dialog
        comparisonResult?.let { result ->
            println("match result - $result")
            AlertDialog(
                onDismissRequest = { comparisonResult = null },
                title = { Text("Comparison Result") },
                text = { Text(buildMatchResultString(result)) },
                confirmButton = {
                    TextButton(onClick = { comparisonResult = null }) { Text("Close") }
                }
            )
        }
    }
}

private fun buildMatchResultString(doc: DocumentMatcher.MatchResult): String {
    return buildString {
        // Common fields for all IDs
        append("Verification Results:\n")
        append("Name Match: ${doc.nameMatch}\n")
        append("DOB Match: ${doc.dobMatch}\n")
        append("Address Match: ${doc.addressMatch}\n")
        append("Face Match: ${doc.faceMatch}\n")
        /*append("Final Score: ${doc.finalScore}\n")
        append("Is Verified: ${if (doc.isVerified) "Yes" else "No"}\n")*/

    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MLkitWithJetpackComposeTheme {
        val navController = rememberNavController()
        val sampleList = remember {
            mutableStateListOf(
                ExtractedDocumentData(
                    documentType = IdType.AADHAAR,
                    name = "John Doe",
                    dob = "01/01/1990",
                    address = "123456",
                    imageUri = null
                ),
                ExtractedDocumentData(
                    documentType = IdType.PAN,
                    name = "JOHN DOE",
                    dob = "01/01/1990",
                    address = null,
                    imageUri = null
                )
            )
        }
        MainScreen(navController = navController, listOfExtractedDocumentData = sampleList)
    }
}
