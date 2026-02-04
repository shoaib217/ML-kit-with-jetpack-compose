package com.example.mlkitwithjetpackcompose.utility

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

    private fun findNameBelowHeader(lines: List<Text.Line>): String? {
        // Find the line index containing "INCOME" or "TAX"
        val headerIndex = lines.indexOfFirst {
            val t = it.text.uppercase()
            t.contains("INCOME") || t.contains("TAX") || t.contains("DEPARTMENT")
        }

        if (headerIndex != -1 && headerIndex + 1 < lines.size) {
            // Check the next 2 lines (to skip 'Govt of India' or multi-line headers)
            for (i in 1..2) {
                if (headerIndex + i >= lines.size) break
                val candidate = lines[headerIndex + i]
                if (isValidName(candidate.text)) {
                    return candidate.text
                }
            }
        }
        return null
    }

    // Helper B: Look above the ID (Bottom-up approach)
    private fun findNameAboveId(lines: List<Text.Line>, panId: String): String? {
        // Find the line containing the PAN ID
        val idLineIndex = lines.indexOfFirst { it.text.contains(panId, ignoreCase = true) }

        if (idLineIndex > 1) {
            // The structure is usually:
            // [Name]
            // [Father's Name]
            // [DOB]
            // [PAN Number]
            // So we look 2 to 3 lines above the ID.

            // Try looking 3 lines up first (Name), then 2 lines up
            for (offset in 3 downTo 1) {
                val targetIndex = idLineIndex - offset
                if (targetIndex >= 0) {
                    val candidate = lines[targetIndex]
                    if (isValidName(candidate.text)) {
                        return candidate.text
                    }
                }
            }
        }
        return null
    }

    // Strict validation to ensure we don't pick up garbage
    private fun isValidName(text: String): Boolean {
        val upper = text.uppercase()
        return text.length > 2 &&
                !text.any { it.isDigit() } && // Names don't have numbers
                !upper.contains("INCOME") &&
                !upper.contains("TAX") &&
                !upper.contains("INDIA") &&
                !upper.contains("GOVT") &&
                !upper.contains("PERMANENT") &&
                !upper.contains("ACCOUNT") &&
                !upper.contains("FATHER") // Skip "Father's Name" label
    }

    // --- PAN LOGIC ---
    private fun extractPanDetails(text: Text): ExtractedDocument {
        val lines = text.textBlocks.flatMap { it.lines }
        val rawTextLines = lines.map { it.text }

        val id = findPattern(rawTextLines, PAN_PATTERN) ?: ""
        val dob = findPattern(rawTextLines, DATE_PATTERN)

        // Strategy A: Find "Income Tax Department" and take the NEXT valid line
        var name = findNameBelowHeader(lines)

        // Strategy B: If A fails, find the PAN Number and look 2-3 lines ABOVE it
        // (Layout: Name -> Father Name -> DOB -> PAN Number)
        if (name == null && id.isNotEmpty()) {
            name = findNameAboveId(lines, id)
        }
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
        val allLines = text.textBlocks.flatMap { it.lines }
        val rawLinesStrings = allLines.map { it.text }

        // 1. Extract ID
        val id = normalizeId(findPattern(rawLinesStrings, DL_PATTERN) ?: "")

        // 2. Extract DOB
        val dobFiltered = rawLinesStrings.filter { it.contains("DOB", true) || it.contains("Birth", true) || it.trim().contains("DOB:", true)  }
        val dob = findPattern(dobFiltered, DATE_PATTERN)

        // 3. Extract Name
        var name = rawLinesStrings.firstOrNull { it.startsWith("Name", true) || it.contains("Name:", true) }
        name = name?.replace("Name", "", true)?.replace(":", "")?.trim()

        // 4. Extract Expiry & Validate
        // Look for keywords like "Valid", "Expiry", "Until", or "NT" (common in Indian DLs)
        val expiryLine = rawLinesStrings.find {
            it.contains("Valid", true) || it.contains("Expiry", true) || it.contains("Until", true) || it.contains("NT", true)
        }
        val expiryDateStr = expiryLine?.let { findPattern(listOf(it), DATE_PATTERN) }
        val isExpired = checkIfExpired(expiryDateStr)

        // 5. Extract Address (Spatial Search)
        // Address is usually a block of text below a line containing "Address"
        val address = extractAddress(allLines)

        return ExtractedDocument(
            type = IdType.DRIVING_LICENSE,
            idNumber = id,
            name = name,
            dob = dob,
            address = address,
            isExpired = isExpired
        )
    }

    private fun extractAddress(allLines: List<Text.Line>): String? {
        val addressHeader = allLines.find { it.text.contains("Address", true) || it.text.contains("Add", true) } ?: return null

        return allLines
            .filter {
                // Find lines physically below the "Address" header but within a reasonable distance
                it.boundingBox!!.top > addressHeader.boundingBox!!.top &&
                        it.boundingBox!!.top < addressHeader.boundingBox!!.top + 400 // Limit search area
            }
            .sortedBy { it.boundingBox!!.top }
            .take(3) // Usually addresses are 2-3 lines
            .joinToString(" ") { it.text }
            .replace("Address", "", true)
            .replace(":", "")
            .trim()
    }

    private fun checkIfExpired(expiryDateStr: String?): Boolean {
        println("expiryDateStr : $expiryDateStr")
        if (expiryDateStr == null) return false
        return try {
            // Handle both DD-MM-YYYY and DD/MM/YYYY
            val cleanDate = expiryDateStr.replace("-", "/")
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
            val expiryDate = sdf.parse(cleanDate)
            expiryDate?.before(java.util.Date()) ?: false
        } catch (e: Exception) {
            false // If date is unparseable, assume not expired for safety or handle error
        }
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