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
    private val PAN_PATTERN = Pattern.compile("[A-Z]{5}[0-9OI]{4}[A-Z]{1}")
    private val AADHAAR_PATTERN = Pattern.compile("[2-9]{1}[0-9]{3}\\s?[0-9]{4}\\s?[0-9]{4}")
    private val DL_PATTERN = Pattern.compile("([A-Z]{2}[- /]?[0-9]{2,3}[- /]?[0-9]{4}[- /]?[0-9]{7})")
    private val PASSPORT_PATTERN = Pattern.compile("[A-Z][0-9]{7}")

    // Valid State Codes to prevent false positives from random alphanumeric OCR noise
    private val STATE_CODES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CH", "DN", "DD", "DL", "GA", "GJ", "HR",
        "HP", "JK", "KA", "KL", "LD", "MP", "MH", "MN", "ML", "MZ", "NL", "OR", "OD",
        "PY", "PB", "RJ", "SK", "TN", "TR", "UP", "WB", "TS", "TG", "UK", "UA", "CG", "JH", "LA"
    )

    private val IGNORE_HEADERS = listOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "INDIA", "GOVERNMENT",
        "MALE", "FEMALE", "DOB", "YEAR", "BIRTH", "ACCOUNT", "NUMBER", "FATHER","ADDRESS"
    )

    private val DL_JUNK_WORDS = listOf(
        "TRANSPORT", "DEPARTMENT", "GOVT", "INDIA", "STATE", "DRIVING", "LICENCE", "LICENSE",
        "UNION", "TERRITORY", "VALID", "AUTHORIZATION", "AUTHORISATION", "ISSUE", "DATE", "CARD",
        "CHIP", "SMART", "ZONAL", "OFFICE", "RTO", "MOTOR", "VEHICLE",
        // New additions to catch vehicle class boilerplate:
        "DRIVE", "FOLLOWING", "CLASS", "THROUGHOUT", "COV", "LMV", "MCWG", "PIN"
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


    fun extractAndSanitizePan(text: String): String? {
        val cleanText = text.uppercase().replace(" ", "")

        // We use the lenient regex to find where the PAN is hidden in the text
        val lenientPattern = Pattern.compile("[A-Z]{5}[0-9OI]{4}[A-Z]{1}")
        val matcher = lenientPattern.matcher(cleanText)

        if (matcher.find()) {
            val rawPan = matcher.group()

            // Split the PAN into its structural parts
            val firstFiveLetters = rawPan.substring(0, 5)
            val middleFourNumbers = rawPan.substring(5, 9)
            val lastLetter = rawPan.substring(9, 10)

            // Fix common OCR errors specifically in the numbers section
            val fixedNumbers = middleFourNumbers
                .replace("O", "0")
                .replace("I", "1")
                .replace("l", "1")
                .replace("S", "5")
                .replace("B", "8")

            // You could do the same for the letter sections (e.g., replacing "0" with "O")

            return "$firstFiveLetters$fixedNumbers$lastLetter" // Returns "AIVPU0924A"
        }

        return null
    }


    fun getDocumentType(fullText: String): IdType? {
        val upper = fullText.uppercase()
        return when {
            // Passport detection: Usually contains "PASSPORT" or the MRZ start pattern "P<"
            upper.contains("PASSPORT") || upper.contains("REPUBLIC OF INDIA") || fullText.contains("P<") -> IdType.PASSPORT
            extractAndSanitizePan(fullText) != null -> IdType.PAN
            upper.contains("MALE") || upper.contains("FEMALE") || AADHAAR_PATTERN.matcher(upper).find() -> IdType.AADHAAR
            upper.contains("DRIVING") || upper.contains("LICENCE") || extractAndSanitizeDl(fullText) != null -> IdType.DRIVING_LICENSE
            else -> null
        }
    }

    /**
     * Extracts and strictly formats a DL number into SSRRYYYYNNNNNNN
     */
    private fun extractAndSanitizeDl(fullText: String): String? {
        val cleanText = fullText.uppercase().replace("[\\s-/]".toRegex(), "")
        val matcher = DL_PATTERN.matcher(cleanText)

        if (matcher.find()) {
            val raw = matcher.group()

            // Fix OCR issues: O->0, I->1, S->5 in the numeric portions
            val stateCode = raw.substring(0, 2)
            val remainder = raw.substring(2)
                .replace("O", "0")
                .replace("I", "1")
                .replace("S", "5")
                .replace("Z", "2")

            val corrected = stateCode + remainder

            if (STATE_CODES.contains(stateCode)) {
                return if (corrected.length >= 15) corrected.substring(0, 15) else corrected
            }
        }
        return null
    }

    /**
     * Normalizes OCR date noise (e.g. 15.02.1998 -> 15-02-1998)
     */
    private fun getSmartDate(text: String): String? {
        val normalized = text.replace("[.\\s,/]+".toRegex(), "-")
        val matcher = DATE_PATTERN.matcher(normalized)

        if (matcher.find()) {
            val dateStr = matcher.group()
            return try {
                val parts = dateStr.split("-")
                val day = parts[0].toInt()
                val month = parts[1].toInt()
                val year = parts[2].toInt()

                // Logical check to ensure it's a real date and not random numbers
                if (month in 1..12 && day in 1..31 && year > 1920) dateStr else null
            } catch (e: Exception) { null }
        }
        return null
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

    private fun extractNameFromPan(lines: List<Text.Line>): String? {
        // 1. ANCHOR METHOD (Most Reliable)
        val nameLabelIndex = lines.indexOfFirst {
            val upper = it.text.uppercase()
            upper.contains("NAME") &&
                    !upper.contains("FATHER") &&
                    !upper.contains("FA THER") &&
                    !upper.contains("THER'S")
        }

        if (nameLabelIndex != -1) {
            // Look at the next 1 to 3 lines after the "Name" label
            for (offset in 1..3) {
                val targetIndex = nameLabelIndex + offset
                if (targetIndex < lines.size) {
                    val candidate = lines[targetIndex].text.trim()
                    if (isValidName(candidate)) {
                        return candidate // Will return "ROMIT ROY"
                    }
                }
            }
        }

        // 2. FALLBACK METHOD (If "Name" label is unreadable)
        val dobIndex = lines.indexOfFirst {
            val upper = it.text.uppercase()
            upper.contains("BIRTH") || upper.contains("DOB")
        }

        if (dobIndex > 1) {
            for (offset in 2..5) {
                val targetIndex = dobIndex - offset
                if (targetIndex >= 0) {
                    val candidate = lines[targetIndex].text.trim()
                    if (isValidName(candidate)) {
                        return candidate
                    }
                }
            }
        }

        return null
    }

    // Strict validation to ensure we don't pick up garbage
    private fun isValidName(text: String): Boolean {
        val upper = text.uppercase()

        // Standalone OCR noise patterns that are NOT names (usually misread Hindi text)
        val standaloneJunk = listOf("HTH", "HTA")

        // Keywords that should invalidate the line if found anywhere (labels)
        val invalidKeywords = listOf(
            "NAME", "FATHER", "INCOME", "TAX", "INDIA", "GOVT",
            "PERMANENT", "ACCOUNT", "SIGNATURE", "HOLDER"
        )

        val words = upper.split(" ").filter { it.isNotBlank() }

        // Fix: If "HTA" is the ONLY thing in the line, it's junk.
        // If it's part of "MEHTA", words.contains("HTA") will be false, and it will pass.
        if (words.size == 1 && standaloneJunk.contains(words[0])) return false

        return text.length > 2 &&
                !text.any { it.isDigit() } &&    // Names don't have numbers
                !text.contains("/") &&           // Names NEVER have forward slashes
                !invalidKeywords.any { upper.contains(it) }
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

        val id = extractAndSanitizePan(text.text) ?: ""
        val dob = findPattern(rawTextLines, DATE_PATTERN)

        // Strategy A: Find "Income Tax Department" and take the NEXT valid line
        var name = findNameBelowHeader(lines)

        // Strategy B: ANCHOR METHOD
        if (name == null && id.isNotEmpty()) {
            name = extractNameFromPan(lines)
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

        // 1. Extract & Sanitize ID
        val id = extractAndSanitizeDl(text.text) ?: ""

        // 2. Extract Dates (Using Smart Parser to handle dots and commas)
        var dob: String? = null
        var expiry: String? = null

        allLines.forEach { line ->
            val txt = line.text.uppercase()
            val foundDate = getSmartDate(txt)

            if (foundDate != null) {
                // Determine if this date is a DOB or Expiry based on context keywords in the same line
                when {
                    txt.contains("DOB") || txt.contains("BIRTH") -> dob = foundDate
                    txt.contains("VALID") || txt.contains("UNTIL") || txt.contains("EXP") || txt.contains("NT") -> expiry = foundDate
                }
            }
        }

        // Fallback for DOB if context keyword was missed by OCR
        if (dob == null) {
            val dobFiltered = rawLinesStrings.filter { it.contains("DOB", true) || it.contains("Birth", true) }
            dob = findPattern(dobFiltered, DATE_PATTERN)
        }

        // 3. Extract Name
        var name: String? = null

        // Attempt A: The "Bottom-Up" Anchor (Most reliable for MH, GJ, and others)
        // Find the line that denotes the Father/Spouse. The Name is usually exactly 1 line above it.
        val relationIndex = allLines.indexOfFirst {
            val upper = it.text.uppercase()
            upper.contains("S/O") || upper.contains("W/O") || upper.contains("D/O") || upper.contains("S/DW")
        }

        if (relationIndex > 0) {
            // Look at the line immediately preceding the relation line
            val candidate = allLines[relationIndex - 1].text.replace(":", "").trim()
            if (isValidDlNameCandidate(candidate)) {
                name = candidate
            }
        }

        // Attempt B: Explicit Label "Name" (If Attempt A failed)
        if (name.isNullOrEmpty()) {
            val nameLabelIndex = allLines.indexOfFirst { it.text.contains("Name", true) && !it.text.contains("Father", true) }
            if (nameLabelIndex != -1) {
                val nameLineText = allLines[nameLabelIndex].text
                val sameLine = nameLineText.replace("Name", "", true).replace(":", "").replace(".", "").trim()

                if (sameLine.length > 2 && isValidDlNameCandidate(sameLine)) {
                    name = sameLine
                } else {
                    // Look 1-2 lines below if "Name" was just a header line
                    for (i in 1..2) {
                        if (nameLabelIndex + i >= allLines.size) break
                        val candidate = allLines[nameLabelIndex + i].text.replace(":", "").trim()
                        if (isValidDlNameCandidate(candidate)) {
                            name = candidate
                            break
                        }
                    }
                }
            }
        }

        // Attempt C: Spatial Context (Below DL Number)
        if (name.isNullOrEmpty() || name.length < 3) {
            name = findDlNameByContext(allLines, id)?.replace(":", "")?.trim()
        }

        // Attempt D: Top of Document (Common in Northern States)
        if (name.isNullOrEmpty() || name.length < 3) {
            name = allLines.map { it.text.replace(":", "").trim() }
                .filter { isValidDlNameCandidate(it) }
                .firstOrNull() // Grabs the first valid non-junk string
        }

        // Final Clean
        if (name != null && name.any { it.isDigit() }) {
            name = name.filter { !it.isDigit() }.trim()
        }

        // 4. Extract Address
        val address = extractAddress(allLines)

        return ExtractedDocument.DrivingLicense(
            id = id,
            name = name,
            dob = dob,
            address = address,
            isExpired = checkIfExpired(expiry)
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
                !text.any { it.isDigit() } &&
                !upper.startsWith("S/O") && !upper.contains(" S/O ") &&
                !upper.startsWith("W/O") && !upper.contains(" W/O ") &&
                !upper.startsWith("D/O") && !upper.contains(" D/O ") &&
                !upper.contains("S/DW") && // Added specifically for MH formats
                !upper.contains("ADDRESS") &&
                !DL_JUNK_WORDS.any { upper.contains(it) }
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