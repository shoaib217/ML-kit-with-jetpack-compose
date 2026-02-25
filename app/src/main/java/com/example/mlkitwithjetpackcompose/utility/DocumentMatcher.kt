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
        
        faceProcessor.compareFaces(img1, img2) { faceScore ->

            // 3. Weighted Average
            val totalScore = (nameScore * WEIGHT_NAME) +
                             (dobScore * WEIGHT_DOB) +
                             (addressScore * WEIGHT_ADDRESS) +
                             (faceScore * WEIGHT_FACE)

            onComplete(MatchResult(
                finalScore = totalScore.toInt(),
                nameMatch = nameScore,
                dobMatch = dobScore,
                addressMatch = addressScore,
                faceMatch = faceScore.toInt(),
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

        val tokens1 = name1.uppercase()
            .replace("[^A-Z ]".toRegex(), "")
            .split(" ")
            .filter { it.isNotBlank() }

        val tokens2 = name2.uppercase()
            .replace("[^A-Z ]".toRegex(), "")
            .split(" ")
            .filter { it.isNotBlank() }

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0

        var totalScore = 0.0
        var matchedCount = 0

        for (t1 in tokens1) {
            var bestMatch = 0

            for (t2 in tokens2) {
                val score = getFuzzyMatch(t1, t2)
                if (score > bestMatch) {
                    bestMatch = score
                }
            }

            if (bestMatch >= 70) { // threshold for valid match
                matchedCount++
                totalScore += bestMatch
            }
        }

        val coverage = matchedCount.toDouble() / maxOf(tokens1.size, tokens2.size)

        val averageScore = if (matchedCount > 0)
            totalScore / matchedCount
        else 0.0

        val finalScore = (averageScore * coverage).toInt()

        return finalScore.coerceIn(0, 100)
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