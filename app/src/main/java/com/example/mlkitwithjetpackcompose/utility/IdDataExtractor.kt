package com.example.mlkitwithjetpackcompose.utility

import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.example.mlkitwithjetpackcompose.data.Gender
import com.example.mlkitwithjetpackcompose.data.IdType
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor.AADHAAR_PATTERN
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor.DATE_PATTERN
import com.example.mlkitwithjetpackcompose.utility.IdDataExtractor.DL_PATTERN
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
        "MALE", "FEMALE", "DOB", "YEAR", "BIRTH", "ACCOUNT", "NUMBER", "FATHER","ADDRESS"
    )

    private val DL_JUNK_WORDS = listOf(
        "TRANSPORT", "DEPARTMENT", "GOVT", "INDIA", "STATE", "DRIVING", "LICENCE", "LICENSE",
        "UNION", "TERRITORY", "VALID", "AUTHORIZATION", "ISSUE", "DATE", "CARD", "CHIP"
    )

    private val BLOCKLIST = listOf("SAMPLE", "SPECIMEN", "VOID", "DUMMY", "LENS", "SHARE", "STOCK")

    /**
     * Orchestrates the extraction of data from a Google ML Kit [Text] object.
     *
     * This function identifies the document type (PAN, Aadhaar, Driving License, or Passport)
     * and applies specific parsing logic for each to extract fields such as ID numbers,
     * names, dates of birth, and expiry dates. It also includes a blocklist check to
     * avoid processing sample or specimen documents.
     *
     * @param visionText The structured text recognized by the ML Kit OCR engine.
     * @return An [ExtractedDocument] containing the parsed details, or `null` if the document
     * type cannot be determined or if the text contains blocklisted terms.
     */
    fun extractData(visionText: Text): ExtractedDocument? {
        val fullText = visionText.text
        if (BLOCKLIST.any { fullText.uppercase().contains(it) }) return null

        val docType = getDocumentType(fullText) ?: return null

        return when (docType) {
            IdType.PAN -> extractPanDetails(visionText)
            IdType.AADHAAR -> extractAadhaarDetails(visionText)
            IdType.DRIVING_LICENSE -> extractDlDetails(visionText)
            IdType.PASSPORT -> extractPassportDetails(visionText)
            IdType.SELFIE -> ExtractedDocument.Selfie()
        }
    }

    /**
     * Extracts passport details from the provided vision text using multiple strategies:
     * 1. Regex pattern matching for standard fields like Passport Number and Dates.
     * 2. Vertical Anchor Strategy to find Names based on "Surname" and "Given Name" labels.
     * 3. MRZ (Machine Readable Zone) fallback for cases where field labels are blurry or missing.
     *
     * @param text The [Text] object containing recognized text blocks and lines from the OCR process.
     * @return An [ExtractedDocument.Passport] object containing the ID, name, DOB, gender, and expiration status.
     */
    private fun extractPassportDetails(text: Text): ExtractedDocument.Passport {
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

        return ExtractedDocument.Passport(
            id = id,
            name = fullName.ifEmpty { null },
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


    fun getDocumentType(fullText: String): IdType? {
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

    /**
     * Extracts details from a Permanent Account Number (PAN) card.
     *
     * This function utilizes two primary strategies to locate the holder's name:
     * 1. **Header Strategy**: Searches for keywords like "Income Tax Department" and looks for valid name
     *    strings in the lines immediately following.
     * 2. **Positional Strategy**: Locates the PAN ID using a regex pattern and searches the lines
     *    directly above it, following the standard PAN card layout (Name -> Father's Name -> DOB -> PAN).
     *
     * @param text The [Text] object containing blocks and lines recognized by ML Kit OCR.
     * @return An [ExtractedDocument.Pan] object containing the identified ID, name, and date of birth.
     */
    private fun extractPanDetails(text: Text): ExtractedDocument.Pan {
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
        return ExtractedDocument.Pan(id, name, dob)
    }


    /**
     * Extracts Aadhaar-specific details from the provided OCR text.
     *
     * This function utilizes a spatial-first approach:
     * 1. Identifies the Aadhaar Number using [AADHAAR_PATTERN].
     * 2. Detects the "Date of Birth" (DOB) line to serve as a spatial anchor.
     * 3. Locates the Name by searching for text blocks physically positioned above the DOB line.
     * 4. Determines Gender by scanning for keywords like "Male" or "Female".
     *
     * @param text The [Text] object containing recognized text blocks and bounding boxes from ML Kit.
     * @return An [ExtractedDocument.Aadhaar] object containing the ID, name, DOB, and gender.
     */
    private fun extractAadhaarDetails(text: Text): ExtractedDocument.Aadhaar {
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

        return ExtractedDocument.Aadhaar(
            id = id,
            name = name,
            dob = dob,
            gender = gender
        )
    }


    /**
     * Extracts Driving License (DL) specific details from the provided [Text] object.
     *
     * This function implements a multi-step extraction strategy:
     * 1. **ID Extraction:** Uses [DL_PATTERN] to find and normalize the license number.
     * 2. **DOB Extraction:** Filters lines for date-related keywords and applies [DATE_PATTERN].
     * 3. **Name Extraction:** First attempts to find a "Name" label; if not found, it uses
     *    spatial context to look for text blocks below the DL number or above the DOB.
     * 4. **Expiry Validation:** Searches for validity keywords (Valid, Until, NT) and
     *    compares the date against the current system time.
     * 5. **Address Extraction:** Uses spatial bounding box logic to capture address blocks
     *    located near the "Address" anchor.
     *
     * @param text The [Text] object containing recognized text blocks and lines from ML Kit OCR.
     * @return An [ExtractedDocument.DrivingLicense] containing the parsed details.
     */
    private fun extractDlDetails(text: Text): ExtractedDocument.DrivingLicense {
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

        return ExtractedDocument.DrivingLicense(
            id = id,
            name = name,
            dob = dob,
            address = address,
            isExpired = isExpired
        )
    }

    /**
     * Extracts secondary data (primarily the address) from the back side of a document.
     *
     * This function uses spatial analysis of text blocks to identify address fields
     * based on common keywords. It returns a partial [ExtractedDocument] containing
     * the address, which can later be merged with data from the front side.
     *
     * @param visionText The [Text] object containing OCR results from the back of the ID.
     * @param expectedType The [IdType] of the document being processed (e.g., AADHAAR, PASSPORT).
     * @return An [ExtractedDocument] populated with the found address, or null if no address is detected.
     */
    fun extractBackSideData(visionText: Text, expectedType: IdType): ExtractedDocument? {
        val allLines = visionText.textBlocks.flatMap { it.lines }

        // Try to find an address
        val address = extractAddress(allLines)

        if (address.isNullOrEmpty()) return null

        // Return a partial document with just the address
        return when (expectedType) {
            IdType.AADHAAR -> ExtractedDocument.Aadhaar(id = "", name = null, dob = null, gender = null, address = address)
            IdType.PASSPORT -> {
                val fatherName = findPassportRelationName(allLines, "FATHER")
                val motherName = findPassportRelationName(allLines, "MOTHER")

                // Special handling for Spouse to allow blank
                val rawSpouse = findPassportRelationName(allLines, "SPOUSE")
                val spouseName = if (rawSpouse != null && !rawSpouse.uppercase().contains("ADDRESS")) {
                    rawSpouse
                } else {
                    null
                }

                ExtractedDocument.Passport(
                    id = "",
                    name = null,
                    dob = null,
                    gender = null,
                    isExpired = false,
                    address = address,
                    fatherName = fatherName,
                    motherName = motherName,
                    spouseName = spouseName,
                )
            }
            // DL/PAN don't usually use this flow, but just in case:
            IdType.DRIVING_LICENSE -> ExtractedDocument.DrivingLicense(id = "", name = null, dob = null, address = address, isExpired = false)
            else -> null
        }
    }

    private fun findPassportRelationName(allLines: List<Text.Line>, labelKeyword: String): String? {
        // 1. Find the header line
        val headerIndex = allLines.indexOfFirst {
            val txt = it.text.uppercase()
            txt.contains(labelKeyword) && (txt.contains("NAME") || txt.contains("OF"))
        }

        if (headerIndex == -1) return null
        val headerLine = allLines[headerIndex]

        // 2. Look at the next 2 lines (to account for small labels like "(Surname)")
        for (i in 1..2) {
            if (headerIndex + i >= allLines.size) break
            val candidateLine = allLines[headerIndex + i]
            val candidateText = candidateLine.text.trim()

            // Validation Logic
            if (isValidNameValue(candidateText)) {
                // Check if this candidate is physically below the header
                val headerBox = headerLine.boundingBox ?: continue
                val candidateBox = candidateLine.boundingBox ?: continue

                // If the candidate's top is below the header's bottom, it's our value
                if (candidateBox.top >= headerBox.top) {
                    return candidateText
                }
            }
        }
        return null
    }


    // Helper to ensure we don't accidentally grab "Address" or "File No" as a name
    private fun isValidNameValue(text: String): Boolean {
        val upper = text.uppercase().trim()

        // List of words that are definitely NOT names
        val invalidWords = listOf(
            "NAME", "FATHER", "MOTHER", "SPOUSE", "ADDRESS",
            "FILE", "PASSPORT", "SURNAME", "GIVEN", "INDIA"
        )

        return text.length >= 2 &&
                !invalidWords.any { upper.contains(it) } &&
                !text.any { it.isDigit() } &&
                !upper.startsWith("FILE NO")
    }


    /**
     * Merges data extracted from the front and back sides of an ID card.
     *
     * This function takes two [ExtractedDocument] objects, one representing the front
     * and one the back. It primarily aims to add the address (usually found on the back)
     * to the more comprehensive data from the front.
     *
     * @param front The [ExtractedDocument] containing data from the front of the ID.
     * @param back The [ExtractedDocument] containing data from the back of the ID (typically just the address).
     * @return A new [ExtractedDocument] instance with the combined data. If the document types
     *         don't have an address field or the logic isn't implemented for them, it returns the original
     *         `front` object.
     */
    fun mergeDetails(front: ExtractedDocument, back: ExtractedDocument): ExtractedDocument {
        return when (front) {
            is ExtractedDocument.Aadhaar -> {
                val backDoc = back as? ExtractedDocument.Aadhaar
                front.copy(address = backDoc?.address) // Add address to front data
            }
            is ExtractedDocument.Passport -> {
                val backDoc = back as? ExtractedDocument.Passport
                front.copy(address = backDoc?.address, fatherName = backDoc?.fatherName, motherName = backDoc?.motherName, spouseName = backDoc?.spouseName) // Add address to front data
            }
            // Logic for others if needed
            else -> front
        }
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