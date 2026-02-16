package com.example.mlkitwithjetpackcompose.utility

import android.content.Context
import android.graphics.Bitmap
import com.example.mlkitwithjetpackcompose.data.ExtractedDocumentData

object DocumentMatcher {

    data class MatchResult(
        val finalScore: Int,      // 0 to 100
        val nameMatch: Int,
        val dobMatch: Int,
        val addressMatch: Int,
        val faceMatch: Int,
        val isVerified: Boolean
    )

    // WEIGHTS: Face is usually the strongest indicator
    private const val WEIGHT_NAME = 0.25
    private const val WEIGHT_DOB = 0.20
    private const val WEIGHT_ADDRESS = 0.15
    private const val WEIGHT_FACE = 0.40

    fun calculateTotalMatch(
        doc1: ExtractedDocumentData,
        img1: Bitmap,
        doc2: ExtractedDocumentData,
        img2: Bitmap,
        context: Context,
        onComplete: (MatchResult) -> Unit
    ) {
        // 1. Calculate Text Scores (Instant)
        val nameScore = calculateNameSimilarity(doc1.name, doc2.name)
        val dobScore = calculateDateSimilarity(doc1.dob, doc2.dob)
        val addressScore = calculateTextSimilarity(doc1.address, doc2.address)

        // 2. Calculate Face Score (Async AI)
        val faceProcessor = FaceRecognitionProcessor(context)
        
        faceProcessor.compareFaces(img1, img2) { similarity ->
            // Convert 0.0-1.0 similarity to 0-100 score
            // Threshold: 0.75 similarity is usually a good "pass" for beard/age diffs
            val facePercentage = (similarity * 100).toInt().coerceIn(0, 100)

            // Adjust curve: Make >80% easier to reach if similarity is > 0.7
            /*val adjustedFaceScore = if (similarity > 0.7) {
                80 + ((similarity - 0.7) * (20 / 0.3)).toInt() // Map 0.7-1.0 to 80-100
            } else {
                (similarity * 100).toInt()
            }*/

            println("similarity $similarity")
            val adjustedFaceScore = when {
                // 1. Highly likely the same person (0.75 - 1.0) -> Score 90-100%
                similarity >= 0.75f -> {
                    90 + ((similarity - 0.75f) * (10 / 0.25f)).toInt()
                }

                // 2. Age/Beard Zone (0.60 - 0.75) -> Map to 80-90%
                // This is where most aging/beard cases fall. We "boost" these scores.
                similarity >= 0.60f -> {
                    80 + ((similarity - 0.60f) * (10 / 0.15f)).toInt()
                }

                // 3. Uncertain Zone (0.45 - 0.60) -> Map to 50-80%
                similarity >= 0.45f -> {
                    50 + ((similarity - 0.45f) * (30 / 0.15f)).toInt()
                }

                // 4. Likely different people
                else -> (similarity * 100).toInt().coerceAtLeast(0)
            }

            println("adjustedFaceScore $adjustedFaceScore")

            // 3. Weighted Average
            val totalScore = (nameScore * WEIGHT_NAME) +
                             (dobScore * WEIGHT_DOB) +
                             (addressScore * WEIGHT_ADDRESS) +
                             (adjustedFaceScore * WEIGHT_FACE)

            onComplete(MatchResult(
                finalScore = totalScore.toInt(),
                nameMatch = nameScore,
                dobMatch = dobScore,
                addressMatch = addressScore,
                faceMatch = adjustedFaceScore.coerceAtMost(100),
                isVerified = totalScore >= 70 // Passing score
            ))
        }
    }

    // --- Text Helpers ---
    private fun calculateTextSimilarity(s1: String?, s2: String?): Int {
        if (s1.isNullOrEmpty() || s2.isNullOrEmpty()) return 0
        val dist = levenshtein(s1.uppercase(), s2.uppercase())
        val maxLen = maxOf(s1.length, s2.length)
        return ((1.0 - dist.toDouble() / maxLen) * 100).toInt()
    }

    private fun calculateNameSimilarity(name1: String?, name2: String?): Int {
        if (name1.isNullOrBlank() || name2.isNullOrBlank()) return 0

        val n1 = name1.uppercase().trim().split(" ").filter { it.isNotBlank() }
        val n2 = name2.uppercase().trim().split(" ").filter { it.isNotBlank() }

        // Segment Extraction
        val first1 = n1.getOrNull(0) ?: ""
        val last1 = if (n1.size > 1) n1.last() else ""
        val middle1 = if (n1.size > 2) n1.subList(1, n1.size - 1).joinToString(" ") else ""

        val first2 = n2.getOrNull(0) ?: ""
        val last2 = if (n2.size > 1) n2.last() else ""
        val middle2 = if (n2.size > 2) n2.subList(1, n2.size - 1).joinToString(" ") else ""

        // 1. First Name Match (Weight: 40%)
        val firstScore = (getFuzzyMatch(first1, first2) * 0.40).toInt()

        // 2. Last Name Match (Weight: 40%)
        val lastScore = (getFuzzyMatch(last1, last2) * 0.40).toInt()

        // 3. Middle Name Match (Weight: 20%)
        val middleScore = when {
            middle1.isEmpty() && middle2.isEmpty() -> 20 // Both blank is a perfect match
            middle1.isEmpty() || middle2.isEmpty() -> 15 // One blank gets partial credit
            else -> (getFuzzyMatch(middle1, middle2) * 0.20).toInt()
        }

        val totalScore = firstScore + lastScore + middleScore

        return if (name1.uppercase().trim() == name2.uppercase().trim()) 100 else totalScore
    }

    /**
     * Calculates similarity between 0 and 100 based on Levenshtein Distance
     */
    private fun getFuzzyMatch(s1: String, s2: String): Int {
        if (s1 == s2) return 100
        if (s1.isEmpty() || s2.isEmpty()) return 0

        val distance = levenshteinDistance(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)

        // Formula: (1 - (changes / total length)) * 100
        return ((1.0 - distance.toDouble() / maxLength) * 100).toInt()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun calculateDateSimilarity(d1: String?, d2: String?): Int {
        if (d1 == null || d2 == null) return 0
        val clean1 = d1.replace("-", "/").replace(".", "/")
        val clean2 = d2.replace("-", "/").replace(".", "/")
        return if (clean1 == clean2) 100 else 0
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1) { 0 }
        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }
}