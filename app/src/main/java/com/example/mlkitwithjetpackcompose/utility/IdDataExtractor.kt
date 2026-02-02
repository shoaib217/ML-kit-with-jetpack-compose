package com.example.mlkitwithjetpackcompose.utility

import android.util.Log
import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

object IdDataExtractor {

    // Regex for specific fields - Normalized to handle common OCR gaps
    private val DATE_PATTERN = Pattern.compile("\\b\\d{2}[/-]\\d{2}[/-]\\d{4}\\b")
    private val PAN_PATTERN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}")
    private val AADHAAR_PATTERN = Pattern.compile("[2-9]{1}[0-9]{3}\\s?[0-9]{4}\\s?[0-9]{4}")
    private val DL_PATTERN = Pattern.compile("[A-Z]{2}[-]?[0-9]{2,3}[-]?[0-9]{4}[-]?[0-9]{7,}")

    private val IGNORE_HEADERS = listOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "INDIA", "GOVERNMENT",
        "MALE", "FEMALE", "DOB", "YEAR", "BIRTH", "ACCOUNT", "NUMBER", "FATHER"
    )

    private val BLOCKLIST = listOf("SAMPLE", "SPECIMEN", "VOID", "DUMMY", "LENS", "SHARE", "STOCK")

    fun extractData(visionText: Text): ExtractedDocument? {
        val fullText = visionText.text
        if (BLOCKLIST.any { fullText.uppercase().contains(it) }) return null

        val docType = getDocumentType(fullText) ?: return null

        return when (docType) {
            IdType.PAN -> extractPanDetails(visionText)
            IdType.AADHAAR -> extractAadhaarDetails(visionText)
            IdType.DRIVING_LICENSE -> extractDlDetails(visionText)
        }
    }

    private fun getDocumentType(fullText: String): IdType? {
        val upper = fullText.uppercase()
        return when {
            PAN_PATTERN.matcher(upper.replace(" ", "")).find() -> IdType.PAN
            upper.contains("MALE") || upper.contains("FEMALE") || AADHAAR_PATTERN.matcher(upper).find() -> IdType.AADHAAR
            upper.contains("DRIVING") && DL_PATTERN.matcher(upper.replace(" ", "")).find() -> IdType.DRIVING_LICENSE
            else -> null
        }
    }

    // --- PAN LOGIC ---
    private fun extractPanDetails(text: Text): ExtractedDocument {
        val lines = text.textBlocks.flatMap { it.lines }
        val rawTextLines = lines.map { it.text }

        val id = findPattern(rawTextLines, PAN_PATTERN) ?: ""
        val dob = findPattern(rawTextLines, DATE_PATTERN)

        // PAN Name is usually the first line that doesn't contain headers or numbers
        val name = lines.map { it.text }.firstOrNull { isPotentialName(it) && !it.contains("TAX", true) }

        return ExtractedDocument(IdType.PAN, id, name, dob)
    }

    // --- AADHAAR LOGIC ---
    private fun extractAadhaarDetails(text: Text): ExtractedDocument {
        val allLines = text.textBlocks.flatMap { it.lines }
        val id = normalizeId(findPattern(allLines.map { it.text }, AADHAAR_PATTERN) ?: "").replace(" ", "")

        // Find the DOB line to use as a spatial anchor
        val dobLine = allLines.find { it.text.contains("DOB", true) || it.text.contains("Year", true) }
        val dob = dobLine?.let { findPattern(listOf(it.text), DATE_PATTERN) }

        // Spatial Search: Name is almost always physically ABOVE the DOB line
        val name = dobLine?.let { findTextAbove(allLines, it) } ?:
        allLines.map { it.text }.firstOrNull { isPotentialName(it) }

        return ExtractedDocument(IdType.AADHAAR, id, name, dob)
    }

    // --- DL LOGIC ---
    private fun extractDlDetails(text: Text): ExtractedDocument {
        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
        val id = normalizeId(findPattern(lines, DL_PATTERN) ?: "")
        val dobFiltered = lines.mapNotNull { if (it.contains("DOB", true)) it else null }
        Log.d("TAG", "extractDlDetails: $dobFiltered")
        val dob = findPattern(dobFiltered, DATE_PATTERN)

        var name = lines.firstOrNull { it.startsWith("Name", true) }
        name = name?.replace("Name", "", true)?.replace(":", "")?.trim()

        return ExtractedDocument(IdType.DRIVING_LICENSE, id, name, dob)
    }

    // --- REFINED HELPERS ---

    /**
     * Fixes common OCR errors where numbers are read as similar-looking letters
     */
    private fun normalizeId(input: String): String {
        return input.replace('O', '0')
            .replace('I', '1')
            .replace('z', '2')
            .replace('S', '5')
    }

    private fun isPotentialName(text: String): Boolean {
        val upper = text.uppercase()
        return text.length > 3 &&
                !text.any { it.isDigit() } &&
                !IGNORE_HEADERS.any { upper.contains(it) }
    }

    /**
     * Uses Bounding Boxes to find the line of text directly above a target line
     */
    private fun findTextAbove(allLines: List<Text.Line>, target: Text.Line): String? {
        return allLines
            .filter { it.boundingBox!!.bottom < target.boundingBox!!.top }
            .maxByOrNull { it.boundingBox!!.bottom } // Get the closest one above
            ?.text?.takeIf { isPotentialName(it) }
    }

    private fun findPattern(lines: List<String>, pattern: Pattern): String? {
        return lines.firstNotNullOfOrNull { line ->
            val clean = line.replace(" ", "")
            val matcher = pattern.matcher(clean)
            if (matcher.find()) matcher.group() else null
        }
    }
}