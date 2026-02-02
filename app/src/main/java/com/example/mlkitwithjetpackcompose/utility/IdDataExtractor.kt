package com.example.mlkitwithjetpackcompose.utility

import com.example.mlkitwithjetpackcompose.data.ExtractedDocument
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

object IdDataExtractor {

    // Regex for specific fields
    private val DATE_PATTERN = Pattern.compile("\\b\\d{2}[/-]\\d{2}[/-]\\d{4}\\b")
    private val PAN_PATTERN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}")
    private val AADHAAR_PATTERN = Pattern.compile("[2-9]{1}[0-9]{3}\\s?[0-9]{4}\\s?[0-9]{4}")
    private val DL_PATTERN = Pattern.compile("[A-Z]{2}[-]?[0-9]{2,3}[-]?[0-9]{4}[-]?[0-9]{7,}")

    // Words to ignore when searching for Names
    private val IGNORE_HEADERS = listOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "INDIA", "GOVERNMENT", "MALE", "FEMALE", "DOB", "YEAR", "BIRTH", "PERMANENT", "ACCOUNT", "NUMBER", "CARD", "FATHER"
    )


    // WEB JUNK BLOCKLIST
    // If these words appear, it's likely a screenshot from Google/Web
    private val BLOCKLIST = listOf(
        "SAMPLE", "SPECIMEN", "VOID", "DUMMY", "ORIGINAL", // Sample markers
        "SEARCH", "LENS", "SHARE", "VISIT", "IMAGES", "RELATED", "STOCK" // Google Image UI elements
    )

    fun getDocumentType(fullText: String): IdType? {
        val upperText = fullText.uppercase()

        // 1. Immediate Rejection for Web Artifacts
        if (BLOCKLIST.any { upperText.contains(it) }) {
            return null
        }

        // 2. Scan line by line for precise ID numbers
        val lines = fullText.split("\n")

        for (line in lines) {
            val cleanLine = line.replace(" ", "").trim()

            // Check PAN
            val panMatcher = PAN_PATTERN.matcher(cleanLine)
            if (panMatcher.find()) {
                return IdType.PAN
            }

            // Check Aadhaar
            // We strip spaces strictly for checking the 12-digit sequence
            val digitOnly = cleanLine.filter { it.isDigit() }
            if (digitOnly.length == 12 && AADHAAR_PATTERN.matcher(line.trim()).find()) {
                // Additional check: Verhoeff algorithm could go here for 100% security
                return IdType.AADHAAR
            }

            // Check DL
            // DL usually requires the keyword "DRIVING" to be present elsewhere in the full text
            // to avoid false positives with other random numbers
            if (DL_PATTERN.matcher(cleanLine).find() && upperText.contains("DRIVING")) {
                return IdType.DRIVING_LICENSE
            }
        }
        return null
    }

    fun extractData(visionText: Text): ExtractedDocument? {
        val fullText = visionText.text

        // 1. Detect Type first
        val documentType = getDocumentType(fullText) ?: return null

        println("documentType - $documentType")

        // 2. Extract fields based on type
        return when (documentType) {
            IdType.PAN -> extractPanDetails(visionText)
            IdType.AADHAAR -> extractAadhaarDetails(visionText)
            IdType.DRIVING_LICENSE -> extractDlDetails(visionText)
        }
    }


    // --- PAN LOGIC ---
    // PAN Structure is usually: Header -> Name -> Father Name -> DOB -> PAN Number
    private fun extractPanDetails(text: Text): ExtractedDocument {
        val blocks = text.textBlocks.flatMap { it.lines }.map { it.text }
        
        val panNumber = findPattern(blocks, PAN_PATTERN) ?: ""
        val dob = findPattern(blocks, DATE_PATTERN)
        
        // Name Heuristic: The first line that isn't a header, isn't the PAN, and isn't the DOB
        val name = blocks.firstOrNull { line ->
            val upper = line.uppercase()
            !upper.containsPattern(PAN_PATTERN) &&
            !upper.containsPattern(DATE_PATTERN) &&
            !IGNORE_HEADERS.any { upper.contains(it) } && 
            line.length > 3 && 
            !line.any { it.isDigit() } // Names rarely have numbers
        }

        return ExtractedDocument(IdType.PAN, panNumber, name, dob)
    }

    // --- AADHAAR LOGIC ---
    // Aadhaar Structure: Name is often the line ABOVE the DOB or Year of Birth
    private fun extractAadhaarDetails(text: Text): ExtractedDocument {
        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
        
        // Clean Aadhaar number (remove spaces)
        var rawUid = findPattern(lines, AADHAAR_PATTERN) ?: ""
        // If regex found spaces (xxxx xxxx xxxx), remove them for the final ID
        val cleanUid = rawUid.replace(" ", "")

        val dobFilterList = lines.mapNotNull { if (it.contains("DOB",true)) it else null }
        val dob = findPattern(dobFilterList, DATE_PATTERN)

        // Name Heuristic: Find the DOB line index, look 1 or 2 lines above it
        var name: String? = null
        val dobIndex = lines.indexOfFirst { it.containsPattern(DATE_PATTERN) || it.contains("DOB", true) || it.contains("Year of Birth", true) }
        
        if (dobIndex > 0) {
            // Check the line immediately above DOB
            val candidate = lines[dobIndex - 1]
            if (!candidate.contains("Government") && !candidate.any { it.isDigit() }) {
                name = candidate
            }
        }

        return ExtractedDocument(IdType.AADHAAR, cleanUid, name, dob)
    }

    // --- DL LOGIC ---
    // DL Structure: "Name: John Doe" or just headers
    private fun extractDlDetails(text: Text): ExtractedDocument {
        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }

        println("lines - $lines")
        val dlNum = findPattern(lines, DL_PATTERN) ?: ""
        val dobFilterList = lines.mapNotNull { if (it.contains("DOB",true)) it else null }
        val dob = findPattern(dobFilterList, DATE_PATTERN)

        // Name Heuristic: Look for line starting with "Name"
        var name = lines.firstOrNull { it.startsWith("Name", true) }
        name = name?.replace("Name", "", true)?.replace(":", "")?.trim()

        return ExtractedDocument(IdType.DRIVING_LICENSE, dlNum.substringAfterLast("DLNo"), name, dob)
    }

    // --- HELPERS ---
    private fun findPattern(lines: List<String>, pattern: Pattern): String? {
        return lines.firstNotNullOfOrNull { line ->
            val cleanLine = line.replace(" ", "").trim()
            val matcher = pattern.matcher(cleanLine)
            if (matcher.find()) matcher.group() else null
        }
    }

    private fun String.containsPattern(pattern: Pattern): Boolean {
        return pattern.matcher(this).find()
    }
}