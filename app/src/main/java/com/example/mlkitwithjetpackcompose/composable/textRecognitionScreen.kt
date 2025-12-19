package com.example.mlkitwithjetpackcompose.composable

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.mlkitwithjetpackcompose.BankStatementDateOCR
import com.example.mlkitwithjetpackcompose.utility.createImageFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

enum class ImageProcess {
    REMOVE_WATERMARK,
    REMOVE_NOISE,
    FORM_16_CHECK
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TextRecognitionScreen() {
    val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    val context = LocalContext.current
    var imageProcess = ImageProcess.REMOVE_NOISE
    var noiseLevel by remember { mutableFloatStateOf(0f) } // Initial denoising strength


    var imageUri by rememberSaveable {
        mutableStateOf<ArrayList<Bitmap>?>(null)
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
    val scope = rememberCoroutineScope()


    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    scope.launch {
                        BankStatementDateOCR.processPdf(
                            context,
                            it,
                            recognizer,
                            showProgressDialog = {
                                showProgressDialog = it
                            },
                            setPdf = { bitmaps, extractedDates ->
                                showProgressDialog = false
                                extractedImageText = extractedDates
                                imageUri = ArrayList(bitmaps)
                            }
                        )
                    }
                }
            }
        }
    )

    val singleImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
//            imageUri = arrayListOf()
            val imageList = arrayListOf<Bitmap>()
            if (uri != null) {
                val bitmap = BitmapFactory.decodeFile(cacheImage(context, uri)?.path)
                imageList?.add(bitmap)
                when (imageProcess) {
                    ImageProcess.REMOVE_WATERMARK -> {
                        val processBitmap = removeWatermark(bitmap)
                        showProgressDialog = false
                        imageUri?.add(processBitmap)

                    }

                    ImageProcess.REMOVE_NOISE -> {
                        //noise reduction
                        /*  val inputMat = bitmapToMat(bitmap)
                          val scaleFactor = 0.5 // Reduce to 50% of original size
                          val resizedImage = resizeImage(inputMat, scaleFactor)*/
                        /* showProgressDialog = false
                         imageUri = imageList*/

                        processImageInBackground(
                            bitmap, onSuccess = {
                                showProgressDialog = false
                                println("onsuccess - ${it.height}")
                                imageList.add(it)
                                imageUri = imageList
                            },
                            onError = {
                                showProgressDialog = false
                                it.printStackTrace()
                                Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                            })
                    }

                    ImageProcess.FORM_16_CHECK -> {
                        showProgressDialog = false
                        val scaleBitmap = scaleBitmap(bitmap, 2.0f)
                        imageUri = arrayListOf(scaleBitmap)
                        processImage2(context, recognizer, scaleBitmap)
                    }
                }

            }
        }
    )


    val singleImagePickerLauncher1 = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            imageUri = arrayListOf()
            uri?.let {
                val bitmap = BitmapFactory.decodeFile(cacheImage(context, it)?.path)
                imageUri?.add(bitmap)
//                val processBitmap = removeWatermark(bitmap)

                //noise reduction
                /* val inputMat = bitmapToMat(bitmap)

                 val denoisedMat = removeNoise(inputMat)
                 val outputBitmap = matToBitmap(denoisedMat)
                 imageUri?.add(outputBitmap)*/

                processImage(context, recognizer, bitmap)
            }
        }
    )


    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                showProgressDialog = true
//                imageUri = uri
                /*recognizer.process(InputImage.fromFilePath(context, imageUri ?: Uri.EMPTY))
                    .addOnSuccessListener { visionText ->
                        val fromDate = "10/12/2021"
                        val toDate = "21/12/2021"
                        var listOfExtractedDate: List<String?>? = null
                        extractedImageText = visionText.text
                        showProgressDialog = false
                        val statementLine = visionText.textBlocks.filter { it.text.lowercase().contains("statement") || it.text.lowercase().contains("account") }
                        statementLine.forEach {
                            Log.d("TAG", "Statement Line : - ${it.text}")
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                                    listOfExtractedDate = extractDatesFromStatement(it.text).map { formatDate(it) }
                                }

                            } catch (e: Exception){
                                e.printStackTrace()
                            }
                        }

                        println("listOfExtractedDate - $listOfExtractedDate")

                        val fromDatePresent = listOfExtractedDate?.any { it == fromDate }
                        val toDatePresent = listOfExtractedDate?.any { it == toDate }
                        if (fromDatePresent == true && toDatePresent == true){
                            Toast.makeText(context,"statement date Match", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context,"statement date not match", Toast.LENGTH_LONG).show()
                        }

                    }
                    .addOnFailureListener {
                        showProgressDialog = false
                        Log.d("TAG", "Exception: ${it.message}")
                    }*/
            }
        })


    LazyColumn(
        state = rememberLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        item {
            if (showProgressDialog) {
                CircularProgressIndicator()
            } else {
                if (extractedImageText.isNotEmpty()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Recognized Text : - ",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        SelectionContainer {
                            Text(text = extractedImageText)
                        }
                    }
                }
                imageUri?.let { bitmaps ->
                    bitmaps.forEach {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "PDF Page",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(800.dp)
                        )
                    }

                }
                /*if (imageUri != null && imageProcess == ImageProcess.REMOVE_NOISE){
                    Slider(
                        value = noiseLevel,
                        onValueChange = { newValue ->
                            noiseLevel = newValue
                            *//*val imageList  = arrayListOf<Bitmap>(imageUri!!.first())
                            showProgressDialog=true
                            processImageInBackground(imageUri!!.first(), onSuccess = {
                                showProgressDialog = false
                                println("onsucess - ${it.height}")
                                imageList.add(it)
                                imageUri = imageList
                            },
                                onError = {
                                    showProgressDialog = false
                                    it.printStackTrace()
                                    Toast.makeText(context,it.message, Toast.LENGTH_SHORT).show()
                                })*//*
                        },
                        onValueChangeFinished = {
                            val imageList  = arrayListOf<Bitmap>(imageUri!!.first())
                            showProgressDialog=true
                            processImageInBackground(imageUri!!.first(), onSuccess = {
                                showProgressDialog = false
                                println("onsuccess - ${it.height}")
                                imageList.add(it)
                                imageUri = imageList
                            },
                                onError = {
                                    showProgressDialog = false
                                    it.printStackTrace()
                                    Toast.makeText(context,it.message, Toast.LENGTH_SHORT).show()
                                },
                                noiseLevel = noiseLevel)
                        },
                        valueRange = 1f..10f, // Adjustable range for noise reduction
                        steps = 9,
                        modifier = Modifier.padding(16.dp)
                    )
                }*/
                if (imageUri != null) {
                    /*AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(10.dp)
                    )*/

                }
                Button(onClick = {
                    showProgressDialog = true
                    /*                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "application/pdf"
                                        }
                                        pickPdfLauncher.launch(intent)*/
                    imageProcess = ImageProcess.REMOVE_NOISE
                    singleImagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(text = "Pick Image for Noise")
                }

                Button(onClick = {
                    showProgressDialog = true
                    /*                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "application/pdf"
                                        }
                                        pickPdfLauncher.launch(intent)*/
                    imageProcess = ImageProcess.REMOVE_WATERMARK
                    singleImagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(text = "Pick Image for Remove WaterMark")
                }

                Button(onClick = {
                    showProgressDialog = true
                    /*val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/pdf"
                    }
                    pickPdfLauncher.launch(intent)*/
                    imageProcess = ImageProcess.FORM_16_CHECK
                    singleImagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(text = "Pick Image for Form 16 check")
                }


                Button(onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/pdf"
                    }
                    pickPdfLauncher.launch(intent)
                }) {
                    Text(text = "Pick Statement")
                }


            }

        }
    }
}

fun cacheImage(context: Context, uri: Uri): File? {
    val inputStream: InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: IOException) {
        Log.e("TAG", "Failed to open input stream for URI: $uri", e)
        return null
    }

    val cacheFile: File = try {
        File.createTempFile("temp_pdf_", ".jpg", context.cacheDir)
    } catch (e: IOException) {
        Log.e("TAG", "Failed to create temp file", e)
        return null
    }

    val outputStream: FileOutputStream? = try {
        FileOutputStream(cacheFile)
    } catch (e: IOException) {
        Log.e("TAG", "Failed to open output stream", e)
        return null
    }

    try {
        inputStream?.use { input ->
            outputStream?.use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: IOException) {
        Log.e("TAG", "Failed to copy Image from URI to cache", e)
        return null
    } finally {
        inputStream?.close()
        outputStream?.close()
    }
    return cacheFile

}

fun processImage(context: Context, recognizer: TextRecognizer, pdfPage: Bitmap) {

    val formHeading = "FORM NO. 16"
    val certificateNumber = "Certificate"
    val panKeyword = "PAN of the Deductor"
    val tanKeyword = "TAN of the Deductor"
    val assessmentYear = "Assessment Year"
    val cit = "CIT (TDS)"

    var formHeadingPresent = false
    var certificateNumberPresent = false
    var panKeywordPresent = false
    var tanKeywordPresent = false
    var assessmentYearPresent = false
    var citPresent = false


    recognizer.process(InputImage.fromBitmap(pdfPage, 0))
        .addOnSuccessListener { visionText ->
            var listOfExtractedDate: ArrayList<RecognizedDate> = arrayListOf()
            visionText.textBlocks.forEach { textBlock ->
                textBlock.lines.forEach { line ->
                    Log.d("TAG", "Text Recognition : - ${line.text}")

                    if (line.text.contains(formHeading, true)) {
                        formHeadingPresent = true
                    }

                    if (line.text.contains(certificateNumber, true)) {
                        certificateNumberPresent = true
                    }

                    if (line.text.contains(panKeyword, true)) {
                        panKeywordPresent = true
                    }

                    if (line.text.contains(tanKeyword, true)) {
                        tanKeywordPresent = true
                    }

                    if (line.text.contains(assessmentYear, true)) {
                        assessmentYearPresent = true
                    }

                    if (line.text.contains(cit, true)) {
                        citPresent = true
                    }


                    /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                         extractDatesFromStatement(line.text)?.let {
                             listOfExtractedDate.add(RecognizedDate(
                                 it,
                                 formatDate(it),
                                 it.dayOfMonth,
                                 it.monthValue,
                                 it.year
                             ))
                         }
                     }*/
                    line.elements.forEach { element ->
//                        Log.d("TAG", "Text Recognition : - ${element.text}")
                        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            extractDatesFromStatement(element.text)?.let {
                                listOfExtractedDate.add(RecognizedDate(
                                    it,
                                    formatDate(it),
                                    it.dayOfMonth,
                                    it.monthValue,
                                    it.year
                                ))
                            }
                        }*/

                    }

                }
            }
            /*val statementLine = visionText.textBlocks.filter { it.text.lowercase().contains("statement") || it.text.lowercase().contains("account") }
            statementLine.forEach {
                Log.d("TAG", "Statement Line : - ${it.text}")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        listOfExtractedDate = extractDatesFromStatement(it.text).map { formatDate(it) }
                    }

                } catch (e: Exception){
                    e.printStackTrace()
                }
            }*/

            if (formHeadingPresent && certificateNumberPresent && panKeywordPresent && tanKeywordPresent && assessmentYearPresent && citPresent) {
                Toast.makeText(context, "form validated", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "invalid form", Toast.LENGTH_LONG).show()
            }

            println("listOfExtractedDate - $listOfExtractedDate")
            val groupedByMonth = listOfExtractedDate.groupBy { it.month }
            println("groupedByMonth -$groupedByMonth")
            val stringBuilder = StringBuilder()
            groupedByMonth.forEach { grpData ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stringBuilder.append("${grpData.value.firstOrNull()?.localDate?.month?.name} : ${grpData.value.size} \n\n")
                }
            }
            val extractedImageText = stringBuilder.toString()

            Log.d("TAG", "extractedImageText: $extractedImageText ")


        }
        .addOnFailureListener {
            Log.d("TAG", "Exception: ${it.message}")
        }
}


fun processImage2(context: Context, recognizer: TextRecognizer, pdfPage: Bitmap) {

    val formHeading = "FORM NO. 16"
    val certificateNumber = "Certificate"
    val panKeyword = "PAN of the Deductor"
    val tanKeyword = "TAN of the Deductor"
    val assessmentYear = "Assessment Year"
    val cit = "CIT (TDS)"

    var formHeadingPresent = false
    var certificateNumberPresent = false
    var panKeywordPresent = false
    var tanKeywordPresent = false
    var assessmentYearPresent = false
    var citPresent = false


    recognizer.process(InputImage.fromBitmap(pdfPage, 0))
        .addOnSuccessListener { visionText ->
            var listOfExtractedDate: ArrayList<RecognizedDate> = arrayListOf()
            visionText.textBlocks.forEach { textBlock ->
                Log.d("TAG", "Text Recognition1 : - ${textBlock.text.trim()}")
                textBlock.lines.forEach { line ->
                    Log.d("TAG", "Text Recognition2 : - ${line.text}")

                    if (line.text.contains(formHeading, true)) {
                        formHeadingPresent = true
                    }

                    if (line.text.contains(certificateNumber, true)) {
                        certificateNumberPresent = true
                    }

                    if (line.text.contains(panKeyword, true)) {
                        panKeywordPresent = true
                    }

                    if (line.text.contains(tanKeyword, true)) {
                        tanKeywordPresent = true
                    }

                    if (line.text.contains(assessmentYear, true)) {
                        assessmentYearPresent = true
                    }

                    if (line.text.contains(cit, true)) {
                        citPresent = true
                    }
                }
            }

            if (formHeadingPresent && certificateNumberPresent && panKeywordPresent /*&& tanKeywordPresent && assessmentYearPresent && citPresent*/) {
                Toast.makeText(context, "form validated", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "invalid form", Toast.LENGTH_LONG).show()
            }
        }
        .addOnFailureListener {
            Log.d("TAG", "Exception: ${it.message}")
        }
}


@RequiresApi(Build.VERSION_CODES.O)
fun extractDatesFromStatement(statement: String): LocalDate? {
    val regexPatterns = listOf(
        Regex("^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(0?[1-9]|[12][0-9]|3[01]),\\s+\\d{4}\$"), // MMMM dd, yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}\$"), // dd MMM yyyy
        Regex("^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(0?[1-9]|[12][0-9]|3[01])\\s+\\d{4}\$"), // MMMM dd yyyy
        Regex("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])\$"), // yyyy-MM-dd
        Regex("^\\d{4}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])\$"), // yyyyMMdd
        Regex("^(0?[1-9]|[12][0-9]|3[01])/(0?[1-9]|1[0-2])/\\d{4}\$"), // dd/MM/yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])-(0?[1-9]|1[0-2])-\\d{4}\$"), // dd-MM-yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])\\.(0?[1-9]|1[0-2])\\.\\d{4}\$"), // dd.MM.yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}\$"), // dd MMM yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])\\s+(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4}\$"), // dd MMMM yyyy
        Regex("^(0?[1-9]|[12][0-9]|3[01])-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-\\d{2}\$"), // dd-MMM-yy
        Regex("^(0?[1-9]|[12][0-9]|3[01])(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\d{2}\$"), // ddMMMyy
        Regex("^(0?[1-9]|1[0-2])/(0?[1-9]|[12][0-9]|3[01])/\\d{4}\$"), // MM/dd/yyyy
        Regex("^(0?[1-9]|1[0-2])-(0?[1-9]|[12][0-9]|3[01])-\\d{4}\$"),// MM-dd-yyyy
        Regex("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(0?[1-9]|[12][0-9]|3[01]),\\s+\\d{4}\$")// MMM dd, yyyy
    )
    val dateFormats = listOf(
        DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART), // Added for "10 Dec 2021"
        DateTimeFormatter.ofPattern("MMMM dd yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),// Added for "October 31 2022"
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("DD MMM YYYY", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("MM-dd-yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART),
        DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.SMART)
    )

    var parsedDate: LocalDate? = null

    val extractedDates = mutableListOf<LocalDate>()
    for (pattern in regexPatterns) {
        val matches = pattern.findAll(statement)
        matches.forEach { matchResult ->
            val matchedDate = matchResult.groupValues[0] // Full match
            Log.d("TAG", "Matched Date: $matchedDate")
            for (formatter in dateFormats) {
                try {
                    val parsedDate = LocalDate.parse(matchedDate, formatter)
                    extractedDates.add(parsedDate)
                    break
                } catch (e: DateTimeParseException) {
                    e.printStackTrace()
                }
            }
        }
    }
    println("extractedDates ---- $extractedDates")
    for (formatter in dateFormats) {
        //use the group 1.
        val date = statement
        try {
            parsedDate = LocalDate.parse(date, formatter)
            println("parsedDate $parsedDate")
            break
        } catch (e: DateTimeParseException) {
        }
    }

    return parsedDate
}


data class RecognizedDate(
    val localDate: LocalDate,
    val date: String?,
    val day: Int,
    val month: Int,
    val year: Int,

    )


fun removeWatermark(inputBitmap: Bitmap): Bitmap {
    val srcMat = Mat()

    // 🔹 Resize Bitmap to avoid memory issues
    val scaledBitmap = inputBitmap.scale(inputBitmap.width / 2, inputBitmap.height / 2)
    Utils.bitmapToMat(scaledBitmap, srcMat)

    // 🔹 Convert to BGR (if input is RGBA)
    val bgrMat = Mat()
    Imgproc.cvtColor(srcMat, bgrMat, Imgproc.COLOR_RGBA2BGR)

    // Convert to grayscale
    val gray = Mat()
    Imgproc.cvtColor(bgrMat, gray, Imgproc.COLOR_BGR2GRAY)

    // Apply adaptive threshold
    val binary = Mat()
    Imgproc.adaptiveThreshold(
        gray,
        binary,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        11,
        2.0
    )

    // Create mask
    val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)

    // Find contours
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(
        binary,
        contours,
        hierarchy,
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )

    // Draw contours on the mask
    for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area > 300) {
            Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1)
        }
    }

    // 🔹 Ensure mask is 1-channel grayscale
    if (mask.type() != CvType.CV_8UC1) {
        Imgproc.cvtColor(mask, mask, Imgproc.COLOR_BGR2GRAY)
    }

    // Apply inpainting
    val resultMat = Mat()
    Photo.inpaint(bgrMat, mask, resultMat, 3.0, Photo.INPAINT_TELEA)

    // Convert Mat back to Bitmap safely
    val outputBitmap =
        Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
    if (!outputBitmap.isRecycled) {
        Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(resultMat, outputBitmap)
    }

    // 🔹 Compress Bitmap before returning (avoids memory errors)
    val stream = ByteArrayOutputStream()
    outputBitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
    val compressedBitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
    stream.close()

    // Release memory
    srcMat.release()
    bgrMat.release()
    gray.release()
    binary.release()
    mask.release()
    resultMat.release()

    return compressedBitmap
}


fun bitmapToMat(bitmap: Bitmap): Mat {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    return mat
}

fun matToBitmap(mat: Mat): Bitmap {
    val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, bitmap)
    return bitmap
}

fun resizeImage(image: Mat, scale: Double): Mat {
    val newWidth = (image.width() * scale).toInt()
    val newHeight = (image.height() * scale).toInt()
    val newSize = Size(newWidth.toDouble(), newHeight.toDouble())
    val resizedImage = Mat()
    Imgproc.resize(image, resizedImage, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
    return resizedImage
}

fun enhanceContrast(src: Mat, dest: Mat) {
    // Basic Contrast Adjustment using Alpha and Beta
    val alpha = 1.75 // Contrast control (1.0-3.0)
    val beta = 0.0 // Brightness control (0-100)
    src.convertTo(dest, CvType.CV_8U, alpha, beta)
}


fun processImageInBackground(
    originalBitmap: Bitmap,
    noiseLevel: Float = 5f,
    onSuccess: (Bitmap) -> Unit,
    onError: (java.lang.Exception) -> Unit,
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val resultBitmap =
                removeNoiseAndEnhanceContrast(originalBitmap, noiseValue = noiseLevel)

            // Switch back to the main thread to update the UI
            withContext(Dispatchers.Main) {
                onSuccess(resultBitmap)
            }
        } catch (e: java.lang.Exception) {
            withContext(Dispatchers.Main) {
                onError(e)
            }
        }
    }
}

fun removeNoiseAndEnhanceContrast(originalBitmap: Bitmap, noiseValue: Float = 5f): Bitmap {
    Log.d("TAG", "removeNoiseAndEnhanceContrast: Starting...")

    var image = Mat()
    var gray = Mat()
    var denoised = Mat()
    var contrastEnhanced = Mat()

    try {
        image = Mat()
        Utils.bitmapToMat(originalBitmap, image)
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Bitmap converted to Mat successfully.")

        val scaleFactor = 0.5 // Reduce to 50% of original size
        val resizedImage = resizeImage(image, scaleFactor)

        gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Image converted to grayscale.")

        // Step 1: Detect Text Regions (Handling Light Gray Text)
        val textMask = Mat()
        Imgproc.adaptiveThreshold(
            gray, textMask, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV, 25, 10.0 // Lower 'C' value to detect light gray text
        )

        // Step 2: Enhance Edges (Ensure Light Gray Text is Not Lost)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Core.bitwise_or(textMask, edges, textMask) // Merge edges with text mask

        // Step 3: Expand Text Mask Using Morphology
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(textMask, textMask, kernel)

        // Step 4: Extract Background (Invert Mask)
        val backgroundMask = Mat()
        Core.bitwise_not(textMask, backgroundMask)

        // Step 5: Compute Noise Level in Background Only
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(gray, mean, stdDev, backgroundMask)
        val noiseLevel = stdDev.get(0, 0)[0]
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Noise level calculated: $noiseLevel")


        denoised = Mat()
        if (noiseLevel < 40) {
            Log.d("TAG", "Background noise is low ($noiseLevel), skipping processing.")
            return originalBitmap
        }

        Log.d("TAG", "removeNoiseAndEnhanceContrast: Applying fastNlMeansDenoisingColored")
        Photo.fastNlMeansDenoisingColored(resizedImage, denoised, noiseValue, noiseValue, 7, 21)


        Log.d("TAG", "removeNoiseAndEnhanceContrast: Denoising completed.")

        contrastEnhanced = Mat()
        enhanceContrast(denoised, contrastEnhanced)
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Contrast enhancement completed.")

        // Convert result back to bitmap
        val resultBitmap =
            createBitmap(contrastEnhanced.cols(), contrastEnhanced.rows())
        Utils.matToBitmap(contrastEnhanced, resultBitmap)
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Mat converted back to Bitmap successfully.")

        return resultBitmap

    } catch (e: java.lang.Exception) {
        Log.e("TAG", "removeNoiseAndEnhanceContrast: Error during image processing", e)
        throw e
    } finally {
        Log.d("TAG", "removeNoiseAndEnhanceContrast: Releasing resources...")
        gray.release()
        denoised.release()
        image.release()
        contrastEnhanced.release()

        Log.d("TAG", "removeNoiseAndEnhanceContrast: Resources released.")
    }
}


fun scaleBitmap(bitmap: Bitmap, scaleFactor: Float): Bitmap {
    val newWidth = (bitmap.width * scaleFactor).toInt()
    val newHeight = (bitmap.height * scaleFactor).toInt()
    return bitmap.scale(newWidth, newHeight)
}
