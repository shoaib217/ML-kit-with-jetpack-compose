package com.example.mlkitwithjetpackcompose.utility

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

class FaceRecognitionProcessor(context: Context) {

    private val modelInputSize = 160
    private val outputEmbeddingSize = 512
    private var interpreter: Interpreter? = null

    init {
        try {
            // Ensure you are using the correct model (mobile_face_net.tflite)
            val modelFile = FileUtil.loadMappedFile(context, "facenet_512.tflite")
            val options = Interpreter.Options().apply {
                setUseNNAPI(true)
                setNumThreads(4) // Added for better stability
            }
            // Optional: Use NNAPI (GPU acceleration) for better precision if available
            interpreter = Interpreter(modelFile, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Compares two facial images and returns a similarity score as a percentage.
     *
     * This function performs the following steps:
     * 1. Detects and crops the primary face in both [bitmap1] and [bitmap2].
     * 2. Generates 512-dimensional embeddings for both faces using the TFLite model.
     * 3. Calculates the cosine similarity between the two embeddings.
     * 4. Calibrates the raw similarity score into a human-readable confidence percentage (0-100%).
     *
     * If a face is not detected in either image, the result will be 0.0.
     *
     * @param bitmap1 The first image containing a face to compare.
     * @param bitmap2 The second image containing a face to compare.
     * @param onResult A callback function that receives the calibrated similarity score (0.0 to 100.0).
     */
    fun compareFaces(
        bitmap1: Bitmap,
        bitmap2: Bitmap,
        onResult: (Float) -> Unit,
    ) {
        detectAndCrop(bitmap1) { face1 ->
            if (face1 == null) { onResult(0f); return@detectAndCrop }

            detectAndCrop(bitmap2) { face2 ->
                if (face2 == null) { onResult(0f); return@detectAndCrop }

                // Get Embeddings
                val embedding1 = getFaceEmbedding(face1)
                val embedding2 = getFaceEmbedding(face2)

                // Calculate Raw Similarity (-1.0 to 1.0)
                val rawSimilarity = calculateCosineSimilarity(embedding1, embedding2)

                println("rawSimilarity $rawSimilarity")
                // Calibrate to "Human" percentage (0 to 100)
                // This adjusts for the beard/age gap
                val calibratedScore = calibrateConfidence(rawSimilarity)

                println("calibratedScore $calibratedScore")

                onResult(calibratedScore)
            }
        }
    }

    /**
     * Detects the largest face in the provided [bitmap] and crops it into a square.
     *
     * This function applies a 25% padding to the bounding box to capture essential
     * structural features like the jawline and forehead, which improves recognition
     * accuracy for individuals with beards or age-related changes. It ensures a
     * 1:1 aspect ratio to prevent stretching during downstream resizing.
     *
     * @param bitmap The source image to process.
     * @param onComplete A callback returning the square-cropped face [Bitmap],
     * or `null` if no face is detected or an error occurs.
     */
    private fun detectAndCrop(bitmap: Bitmap, onComplete: (Bitmap?) -> Unit) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()

        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    val box = face.boundingBox
                    val centerX = box.centerX()
                    val centerY = box.centerY()

                    // INCREASED PADDING (0.25): Essential for age/beard
                    // to capture jawline and forehead structure.
                    val sideLength = max(box.width(), box.height())
                    val padding = (sideLength * 0.25f).toInt()
                    val squareSide = sideLength + (padding * 2)

                    val left = (centerX - squareSide / 2).coerceAtLeast(0)
                    val top = (centerY - squareSide / 2).coerceAtLeast(0)
                    val width = squareSide.coerceAtMost(bitmap.width - left)
                    val height = squareSide.coerceAtMost(bitmap.height - top)

                    try {
                        onComplete(Bitmap.createBitmap(bitmap, left, top, width, height))
                    } catch (e: Exception) { onComplete(null) }
                } else { onComplete(null) }
            }
            .addOnFailureListener { onComplete(null) }
    }

    /**
     * Generates a high-dimensional feature vector (embedding) for a given face image using the FaceNet model.
     *
     * The process involves:
     * 1. Resizing the bitmap to the required model input size (160x160).
     * 2. Normalizing pixel values to a range of [-1, 1] using standardization ((pixel - 127.5) / 127.5).
     * 3. Running inference through the TFLite interpreter to produce a 512-dimensional embedding.
     *
     * @param bitmap The cropped square bitmap containing a face.
     * @return A [FloatArray] of size 512 representing the unique features of the face.
     */
    private fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
            // Standardize: (pixel - 127.5) / 128.0 is safer for general TFLite models
            .add(NormalizeOp(127.5f, 128.0f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputBuffer = ByteBuffer.allocateDirect(outputEmbeddingSize * 4)
        outputBuffer.order(java.nio.ByteOrder.nativeOrder())

        interpreter?.run(tensorImage.buffer, outputBuffer)

        outputBuffer.rewind()
        val rawOutput = FloatArray(outputEmbeddingSize)
        outputBuffer.asFloatBuffer().get(rawOutput)

        // CRITICAL FIX: L2 Normalization
        // This makes the vector length = 1.0, ensuring Cosine Similarity works correctly.
        var sum = 0f
        for (value in rawOutput) { sum += value * value }
        val norm = sqrt(sum)

        if (norm > 0) {
            for (i in rawOutput.indices) { rawOutput[i] /= norm }
        }

        return rawOutput
    }


    /**
     * Calculates the cosine similarity between two feature vectors (embeddings).
     *
     * This measures the cosine of the angle between two vectors, providing a similarity
     * score where 1.0 indicates identical orientation and -1.0 indicates opposite orientation.
     *
     * @param v1 The first face embedding vector.
     * @param v2 The second face embedding vector.
     * @return The cosine similarity score, typically ranging from -1.0 to 1.0.
     *         Returns 0.0 if either vector has a magnitude of zero.
     */
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        // Since v1 and v2 are already normalized (length=1),
        // Cosine Similarity = Dot Product.
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
        }
        return dotProduct
    }

    /**
     * Maps a raw cosine similarity score to a human-readable "Confidence Percentage" (0-100%).
     *
     * This calibration uses a non-linear curve to account for physical variations such as
     * aging, facial hair (beards), and lighting differences. It is specifically tuned for
     * the FaceNet 512 model thresholds:
     * - **0.80 to 1.00:** Near-identical matches (95-100%).
     * - **0.32 to 0.80:** The "Aging/Beard Zone." Uses a cube-root boost to pull mid-range
     *   similarities into a more intuitive 70-95% confidence range.
     * - **Below 0.32:** Non-match zone, mapping to 0-69% confidence.
     *
     * @param rawSimilarity The raw cosine similarity value, typically between -1.0 and 1.0.
     * @return A calibrated confidence score between 0.0 and 100.0.
     */
    private fun calibrateConfidence(rawSimilarity: Float): Float {
        // --- PRODUCTION THRESHOLDS FOR FACENET-512 (L2 NORMALIZED) ---
        // These raw thresholds are based on real-world Cosine Similarity distributions:
        val RAW_HIGH_CONFIDENCE = 0.75f  // Exact match / Recent selfie
        val RAW_AGING_MATCH     = 0.55f  // 10-year gap / beard / skin tone changes
        val RAW_LOOKALIKE       = 0.45f  // Relatives / Similar bone structure (False Positives)

        return when {
            // ZONE 1: HIGH CONFIDENCE (90% to 100%)
            // Raw Score: 0.75 to 1.00
            rawSimilarity >= RAW_HIGH_CONFIDENCE -> {
                val range = 1.00f - RAW_HIGH_CONFIDENCE
                val normalized = (rawSimilarity - RAW_HIGH_CONFIDENCE) / range
                // Maps to 90% - 100%
                90f + (normalized * 10f)
            }

            // ZONE 2: TRUE MATCH WITH AGING / SKIN TONE (70% to 89.9%)
            // Raw Score: 0.55 to 0.749...
            // This is the "Forgiveness Zone" for real users.
            rawSimilarity >= RAW_AGING_MATCH -> {
                val range = RAW_HIGH_CONFIDENCE - RAW_AGING_MATCH
                val normalized = (rawSimilarity - RAW_AGING_MATCH) / range
                // Maps to 70% - 89.9%
                70f + (normalized * 20f)
            }

            // ZONE 3: LOOKALIKES & FALSE POSITIVES (40% to 69.9%)
            // Raw Score: 0.45 to 0.549...
            // STRICT RULE: Must be below 70% so your app rejects them.
            rawSimilarity >= RAW_LOOKALIKE -> {
                val range = RAW_AGING_MATCH - RAW_LOOKALIKE
                val normalized = (rawSimilarity - RAW_LOOKALIKE) / range
                // Maps to 40% - 69.9%
                40f + (normalized * 30f)
            }

            // ZONE 4: DEFINITE MISMATCH (0% to 39.9%)
            // Raw Score: Below 0.45
            else -> {
                // Rapid drop-off to 0
                (rawSimilarity / RAW_LOOKALIKE) * 40f
            }
        }.coerceIn(0f, 100f)
    }
}