package com.example.mlkitwithjetpackcompose.utility

import java.util.regex.Pattern

enum class IdType { AADHAAR, PAN, DRIVING_LICENSE }

data class IdResult(val type: IdType, val number: String)

object IdPatternValidator {

    // STRICT REGEX PATTERNS
    // PAN: 5 letters, 4 digits, 1 letter (e.g., ABCDE1234F)
    private val PAN_PATTERN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}")

    // Aadhaar: 12 digits, often spaced (e.g., 1234 5678 9012)
    // We look for 12 digits that don't start with 0 or 1
    private val AADHAAR_PATTERN = Pattern.compile("^[2-9]{1}[0-9]{3}\\s?[0-9]{4}\\s?[0-9]{4}$")

    // DL: Matches common Indian DL formats (e.g., MH-12-2022-1234567)
    // This is a "loose" strict check because states vary wildly
    private val DL_PATTERN = Pattern.compile("[A-Z]{2}[-]?[0-9]{2,3}[-]?[0-9]{4}[-]?[0-9]{7,}")

    // WEB JUNK BLOCKLIST
    // If these words appear, it's likely a screenshot from Google/Web
    private val BLOCKLIST = listOf(
        "SAMPLE", "SPECIMEN", "VOID", "DUMMY", "ORIGINAL", // Sample markers
        "SEARCH", "LENS", "SHARE", "VISIT", "IMAGES", "RELATED", "STOCK" // Google Image UI elements
    )

    fun validate(fullText: String): IdResult? {
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
                return IdResult(IdType.PAN, panMatcher.group())
            }

            // Check Aadhaar
            // We strip spaces strictly for checking the 12-digit sequence
            val digitOnly = cleanLine.filter { it.isDigit() }
            if (digitOnly.length == 12 && AADHAAR_PATTERN.matcher(line.trim()).find()) {
                // Additional check: Verhoeff algorithm could go here for 100% security
                return IdResult(IdType.AADHAAR, digitOnly)
            }

            // Check DL
            // DL usually requires the keyword "DRIVING" to be present elsewhere in the full text
            // to avoid false positives with other random numbers
            if (DL_PATTERN.matcher(cleanLine).find() && upperText.contains("DRIVING")) {
                return IdResult(IdType.DRIVING_LICENSE, cleanLine)
            }
        }
        return null
    }
}