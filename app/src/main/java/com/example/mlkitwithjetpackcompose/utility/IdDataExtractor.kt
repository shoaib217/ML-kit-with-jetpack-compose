package com.example.mlkitwithjetpackcompose.utility

import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.data.Gender
import com.example.mlkitwithjetpackcompose.data.IdType
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

object IdDataExtractor {

    // Regex for specific fields - Normalized to handle common OCR gaps
    private val DATE_PATTERN = Pattern.compile("[0-3]?\\d[/-][0-1]?\\d[/-]\\d{4}")
    private val PAN_PATTERN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}")
    private val AADHAAR_PATTERN = Pattern.compile("[2-9]{1}[0-9]{3}\\s?[0-9]{4}\\s?[0-9]{4}")
    private val DL_PATTERN = Pattern.compile("[A-Z]{2}[-]?[0-9]{2,3}[-]?[0-9]{4}[-]?[0-9]{7,}")
    private val PASSPORT_PATTERN = Pattern.compile("[A-Z][0-9]{7}")

    private val IGNORE_HEADERS = listOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "INDIA", "GOVERNMENT",
        "MALE", "FEMALE", "DOB", "YEAR", "BIRTH", "ACCOUNT", "NUMBER", "FATHER"
    )

    private val DL_JUNK_WORDS = listOf(
        "TRANSPORT", "DEPARTMENT", "GOVT", "INDIA", "STATE", "DRIVING", "LICENCE", "LICENSE",
        "UNION", "TERRITORY", "VALID", "AUTHORIZATION", "ISSUE", "DATE", "CARD", "CHIP"
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
            IdType.PASSPORT -> extractPassportDetails(visionText)
        }
    }

    private fun extractPassportDetails(text: Text): ExtractedDocument {
        val allLines = text.textBlocks.flatMap { it.lines }
        val rawStrings = allLines.map { it.text }

        // 1. Standard Field Extraction
        val id = findPattern(rawStrings, PASSPORT_PATTERN) ?: ""

        val dates = allLines.mapNotNull { findPattern(listOf(it.text), DATE_PATTERN) }.distinct()
        val dob = dates.minOrNull()
        val expiryDateStr = dates.maxOrNull()
        val isExpired = checkIfExpired(expiryDateStr)
        val gender = detectGender(rawStrings)

        // 2. NAME EXTRACTION (Vertical Anchor Strategy)
        var surname: String? = null
        var givenName: String? = null

        allLines.forEachIndexed { index, line ->
            val txt = line.text.uppercase()

            // Find Surname and look at the line immediately following it
            if (txt.contains("SURNAME") && index + 1 < allLines.size) {
                val candidate = allLines[index + 1].text
                if (isPotentialName(candidate)) {
                    surname = candidate
                }
            }

            // Find Given Name and look at the line immediately following it
            if (txt.contains("GIVEN NAME") && index + 1 < allLines.size) {
                val candidate = allLines[index + 1].text
                if (isPotentialName(candidate)) {
                    givenName = candidate
                }
            }
        }

        // 3. MRZ Fallback (Standardized format: P<IND...)
        // This catches names if labels are blurry or misaligned
        if (surname == null || givenName == null) {
            val mrzLine = rawStrings.find { it.contains("P<IND", true) }
            if (mrzLine != null) {
                val (mrzSurname, mrzGiven) = parseMrzName(mrzLine)
                if (surname == null) surname = mrzSurname
                if (givenName == null) givenName = mrzGiven
            }
        }

        val fullName = "${givenName ?: ""} ${surname ?: ""}".trim()

        return ExtractedDocument(
            type = IdType.PASSPORT,
            idNumber = id,
            name = if (fullName.isNotEmpty()) fullName else null,
            dob = dob,
            gender = gender,
            isExpired = isExpired
        )
    }


    /**
     * Parses the Machine Readable Zone (MRZ) found at the bottom of Passports.
     * Format: P<IND[SURNAME]<<[GIVEN<NAME]
     */
    private fun parseMrzName(mrz: String): Pair<String?, String?> {
        return try {
            // Find where the actual name starts (after P<IND or similar country code)
            val nameData = mrz.substring(5)
            val parts = nameData.split("<<")

            val surname = parts.getOrNull(0)?.replace("<", " ")?.trim()
            val givenName = parts.getOrNull(1)?.replace("<", " ")?.trim()

            Pair(surname, givenName)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Enhanced Gender Detection for all IDs
     */
    private fun detectGender(lines: List<String>): Gender? {
        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.contains("TRANSGENDER") -> return Gender.TRANSGENDER
                // Checks for Female/F/F-F
                upper.contains("FEMALE") || upper == "F" || upper == "F/F" -> return Gender.FEMALE
                // Checks for Male/M/M-M
                upper.contains("MALE") || upper == "M" || upper == "M/M" -> return Gender.MALE
            }
        }
        return null
    }


    private fun getDocumentType(fullText: String): IdType? {
        val upper = fullText.uppercase()
        return when {
            // Passport detection: Usually contains "PASSPORT" or the MRZ start pattern "P<"
            upper.contains("PASSPORT") || upper.contains("REPUBLIC OF INDIA") || fullText.contains("P<") -> IdType.PASSPORT
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
        val rawStrings = allLines.map { it.text }
        val id = normalizeId(findPattern(allLines.map { it.text }, AADHAAR_PATTERN) ?: "").replace(" ", "")

        // Find the DOB line to use as a spatial anchor
        val dobLine = allLines.find { it.text.contains("DOB", true) || it.text.contains("Year", true) }
        val dob = dobLine?.let { findPattern(listOf(it.text), DATE_PATTERN) }

        // Spatial Search: Name is almost always physically ABOVE the DOB line
        val name = dobLine?.let { findTextAbove(allLines, it) } ?:
        allLines.map { it.text }.firstOrNull { isPotentialName(it) }

        val gender = detectGender(rawStrings)

        return ExtractedDocument(
            type = IdType.AADHAAR,
            idNumber = id,
            name = name,
            dob = dob,
            gender = gender
        )
    }

    // --- DL LOGIC ---
    private fun extractDlDetails(text: Text): ExtractedDocument {
        val allLines = text.textBlocks.flatMap { it.lines }
        val rawLinesStrings = allLines.map { it.text }

        // 1. Extract ID
        val id = normalizeId(findPattern(rawLinesStrings, DL_PATTERN) ?: "")

        // 2. Extract DOB
        val dobFiltered = rawLinesStrings.filter { it.contains("DOB", true) || it.contains("Birth", true) || it.trim().contains("DOB:", true)  }
        println("dobFiltered : $dobFiltered")
        val dob = findPattern(dobFiltered, DATE_PATTERN)

        // 3. Extract Name
        // Attempt A: Explicit Label "Name"
        var name = rawLinesStrings.firstOrNull { it.contains("Name", true) }
            ?.replace("Name", "", true)
            ?.replace(":", "")
            ?.replace(".", "")
            ?.trim()

        // Attempt B: Spatial Context (Below DL Number, Above S/o or DOB)
        if (name.isNullOrEmpty() || name.length < 3) {
            name = findDlNameByContext(allLines, id)
        }

        // Final Clean: Remove any accidentally captured digits or symbols
        if (name != null && name.any { it.isDigit() }) {
            // If name contains digits, it's likely garbage. Reset to null or clean it.
            name = name.filter { !it.isDigit() }.trim()
        }

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

    private fun findDlNameByContext(lines: List<Text.Line>, dlNumber: String): String? {
        // Find the index of the line containing the DL Number
        val dlIndex = lines.indexOfFirst {
            val normText = it.text.replace(" ", "").replace("-", "")
            val normDl = dlNumber.replace(" ", "").replace("-", "")
            normText.contains(normDl, true) || it.text.contains("DL No", true)
        }

        if (dlIndex != -1) {
            // Look at the next 3 lines below the DL Number
            // The name is usually here.
            for (i in 1..3) {
                if (dlIndex + i >= lines.size) break
                val candidateLine = lines[dlIndex + i].text

                // Validate this candidate
                if (isValidDlNameCandidate(candidateLine)) {
                    return candidateLine
                }
            }
        }

        // Fallback: Sometimes Name is at the very top (above DL number), but below "State"
        // This is riskier, so we check strictly.
        return lines.firstOrNull { isValidDlNameCandidate(it.text) }?.text
    }

    private fun isValidDlNameCandidate(text: String): Boolean {
        val upper = text.uppercase()

        return text.length > 3 &&
                !text.any { it.isDigit() } &&                  // No numbers in names
                !upper.contains("S/O") &&                      // Not Father's name line
                !upper.contains("W/O") &&                      // Not Husband's name line
                !upper.contains("D/O") &&                      // Not Daughter's name line
                !upper.contains("ADDRESS") &&                  // Not Address
                !DL_JUNK_WORDS.any { upper.contains(it) }      // Not a header like "Transport Dept"
    }

    private fun extractAddress(allLines: List<Text.Line>): String? {
        // 1. Find the anchor (The line containing "Address" or "Add")
        val addressHeader = allLines.find {
            it.text.contains("Address", true) || it.text.contains("Add:", true)|| it.text.contains("Add", true)
        } ?: return null

        val headerBox = addressHeader.boundingBox ?: return null

        // 2. Get text on the SAME line (to the right of the label)
        val sameLineText = addressHeader.text
            .replace("Address", "", true)
            .replace("Add", "", true)
            .replace(":", "")
            .trim()

        // 3. Get lines physically BELOW the header
        val linesBelow = allLines
            .filter { line ->
                val box = line.boundingBox ?: return@filter false
                // Logic: Top of line is below the header, and it's horizontally aligned
                box.top > headerBox.top &&
                        box.top < headerBox.top + 450 && // Vertical threshold
                        box.left < headerBox.right + 200 // Ensure it's not a different column
            }
            .sortedBy { it.boundingBox!!.top }
            .take(3)
            .map { it.text }

        // 4. Combine same-line text with lines below
        val fullAddress = mutableListOf<String>()
        if (sameLineText.isNotEmpty()) {
            fullAddress.add(sameLineText)
        }
        fullAddress.addAll(linesBelow)

        return fullAddress.joinToString(" ")
            .replace(Regex("\\s+"), " ") // Clean extra spaces
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
            // Strategy 1: strict match on original line (Fixes "DOB 15-02-1997")
            val matcherOriginal = pattern.matcher(line.trim())
            if (matcherOriginal.find()) {
                return@firstNotNullOfOrNull matcherOriginal.group()
            }

            // Strategy 2: Spaceless match (Fixes "1 5 - 0 2 - 1 9 9 7")
            val cleanLine = line.replace(" ", "")
            val matcherClean = pattern.matcher(cleanLine)
            if (matcherClean.find()) {
                return@firstNotNullOfOrNull matcherClean.group()
            }

            null
        }
    }
}