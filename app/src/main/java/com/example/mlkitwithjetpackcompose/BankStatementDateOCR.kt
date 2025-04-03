package com.example.mlkitwithjetpackcompose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.example.mlkitwithjetpackcompose.composable.RecognizedDate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private const val TAG = "PdfProcessing"

object BankStatementDateOCR {

    suspend fun processPdf(context: Context, uri: Uri, recognizer: TextRecognizer, setPdf: (List<Bitmap>, String) -> Unit,showProgressDialog: (Boolean) -> Unit) {
        withContext(Dispatchers.Main) {
            //showProgressDialog = true  // Assuming you have a UI state for progress
            showProgressDialog(true)
        }

        val pdfFileResult = withContext(Dispatchers.IO) {
            copyPdfFromUriToCache(context, uri)
        }

        if (pdfFileResult.isSuccess) {
            val pdfFile = pdfFileResult.getOrThrow()
            withContext(Dispatchers.Default) { // Or Dispatchers.IO if readPdf involves I/O
                readPdf(
                    pdf = pdfFile,
                    recognizer = recognizer,
                    showProgressDialog = showProgressDialog,
                    setPdf = { bitmaps, extractedText ->
                        setPdf(bitmaps, extractedText)  // Directly call the provided callback
                    }
                )
            }
        } else {
            val exception = pdfFileResult.exceptionOrNull()
            withContext(Dispatchers.Main) {
                //showErrorMessage("Failed to copy PDF: ${exception?.message}")
                //showProgressDialog = false
                showProgressDialog(false)
                Log.e(TAG, "Failed to copy PDF: ${exception?.message}") // Log error instead of UI update
            }
        }
    }

    private fun copyPdfFromUriToCache(context: Context, uri: Uri): Result<File> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IOException("Could not open input stream for URI: $uri"))

            val cacheFile = File.createTempFile("temp_pdf_", ".pdf", context.cacheDir)
            val outputStream = FileOutputStream(cacheFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(cacheFile)
        } catch (e: IOException) {
            Log.e(TAG, "Error copying PDF: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun readPdf(pdf: File?, recognizer: TextRecognizer,showProgressDialog: (Boolean) -> Unit, setPdf: (List<Bitmap>, String) -> Unit) {
        if (pdf == null) {
            Log.e(TAG, "PDF file is null")
            showProgressDialog(false)
            return  // Or handle this more gracefully, e.g., by calling setPdf with empty results
        }

        try {
            val bitmaps: MutableList<Bitmap> = mutableListOf() // Use MutableList
            val parcelFileDescriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            Log.d(TAG, "openPdfRenderer: pageCount ${pdfRenderer.pageCount}")

            if (pdfRenderer.pageCount > 0) {
                for (i in 0 until pdfRenderer.pageCount) {
                    val currentPage = pdfRenderer.openPage(i)

                    // High-resolution bitmap
                    val scaleFactor = 2
                    val bitmap =
                        createBitmap(currentPage.width * scaleFactor, currentPage.height * scaleFactor)

                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    bitmaps.add(bitmap)
                    currentPage.close()
                }
            }
            pdfRenderer.close() // Close PdfRenderer
            parcelFileDescriptor.close() // Close ParcelFileDescriptor
            Log.d(TAG, "readPdf: bitmapSize -${bitmaps.size}")

            processBankStatement(recognizer, bitmaps.subList(0,1)) { recognizedDates ->
                val groupedByMonth = recognizedDates.groupBy { it.month }
                println("groupedByMonth -$groupedByMonth")

                val extractedImageText = buildString {
                    groupedByMonth.forEach { (_, group) ->
                        val firstDate = group.firstOrNull()?.localDate
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            append("${firstDate?.month?.name ?: "Unknown Month"}: ${group.size}\n\n")
                        }
                    }
                }
                Log.d(TAG, "extractedImageText: $extractedImageText ")
                setPdf(bitmaps, extractedImageText)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading PDF: ${e.message}", e)
            showProgressDialog(false)
            // Consider calling setPdf with empty data to signal failure to the UI
            //  setPdf(emptyList(), "Error extracting data from PDF")
        }
    }

    private fun processBankStatement(
        recognizer: TextRecognizer, bitmaps: List<Bitmap>, // Use List
        onDateExtracted: (List<RecognizedDate>) -> Unit,  // Use List
    ) {
        val listOfExtractedDate: MutableList<RecognizedDate> = mutableListOf()

        bitmaps.forEachIndexed { pos, bitmap ->  // Use forEach (no index needed)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    visionText.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            Log.v(TAG, "Extracted line: ${line.text}") // Use verbose logging
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val dates = extractDates(line.text)
                                if (!dates.isNullOrBlank()) {
                                    Log.d(TAG, "Extracted dates: $dates")
                                    val formattedDates = formatDate(dates)
                                    formattedDates?.let {
                                        listOfExtractedDate.add(it)
                                    }
                                }
                            }
                        }
                    }

                    if (bitmaps.size == (pos + 1)){
                        onDateExtracted(listOfExtractedDate)
                    }

                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Text recognition failed: ${e.message}", e)  // Use warning level
                }

        }
    }

    private fun extractDates(text: String): String? {
        val datePatterns = arrayListOf(Regex("""\b\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4}\b"""),
            Regex("""\b(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[0-2])/\d{2}\b"""))
        var result: String? = null
        for (pattern in datePatterns) {
            result = pattern.find(text)?.value
            if(!result.isNullOrBlank()){
                break
            }
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatDate(date: String): RecognizedDate? {
        val inputFormatters = arrayListOf(
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH))
        val outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd") // Desired format

        val inputFormatter = inputFormatters.find { date.isParsable(it) }

        if (inputFormatter == null){
            return null
        }

        return try {
            val normalizedDateString = date.replace(Regex("\\p{Nd}+")) {
                it.value.map { Character.getNumericValue(it) }.joinToString("")
            }
            val parsedDate = LocalDate.parse(normalizedDateString.trim(), inputFormatter)
            val parsedDateStr = parsedDate.format(outputFormatter)
            RecognizedDate(
                localDate = parsedDate,
                date = parsedDateStr,
                day = parsedDate.dayOfMonth,
                month = parsedDate.monthValue,
                year = parsedDate.year
            )
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Invalid date format: $date, skipping.", e)
            null // Skip invalid dates
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun String.isParsable(formatter: DateTimeFormatter): Boolean {
        return try {
            LocalDate.parse(this, formatter)
            true // Parsing succeeded, it's a valid date
        } catch (e : DateTimeParseException) {
            false // Parsing failed with this formatter, try the next
        }
    }

}