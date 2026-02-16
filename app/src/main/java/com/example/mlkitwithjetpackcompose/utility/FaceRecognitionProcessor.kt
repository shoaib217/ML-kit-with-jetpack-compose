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
import kotlin.math.sqrt

class FaceRecognitionProcessor(context: Context) {

    // Configuration for MobileFaceNet
    private val modelInputSize = 112 
    private val outputEmbeddingSize = 192 
    private var interpreter: Interpreter? = null

    init {
        try {
            // Load the model from assets/mobile_face_net.tflite
            val modelFile = FileUtil.loadMappedFile(context, "mobilefacenet.tflite")
            val options = Interpreter.Options()
            interpreter = Interpreter(modelFile, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Main Function: Takes two images, finds faces, and calculates similarity.
     * Returns a score between 0.0 (No Match) and 1.0 (Perfect Match).
     */
    fun compareFaces(
        bitmap1: Bitmap,
        bitmap2: Bitmap,
        onResult: (Float) -> Unit
    ) {
        // 1. Detect and Crop Face from Image 1
        detectAndCrop(bitmap1) { face1 ->
            if (face1 == null) { onResult(0f); return@detectAndCrop }

            // 2. Detect and Crop Face from Image 2
            detectAndCrop(bitmap2) { face2 ->
                if (face2 == null) { onResult(0f); return@detectAndCrop }

                // 3. Get Embeddings (Digital Fingerprints)
                val embedding1 = getFaceEmbedding(face1)
                val embedding2 = getFaceEmbedding(face2)

                // 4. Calculate Similarity (Cosine Similarity)
                // This is robust to lighting, beards, and age.
                val similarity = calculateCosineSimilarity(embedding1, embedding2)
                onResult(similarity)
            }
        }
    }

    private fun detectAndCrop(bitmap: Bitmap, onComplete: (Bitmap?) -> Unit) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    val box = face.boundingBox
                    // Add 10% margin for context
                    val margin = (box.width() * 0.1f).toInt()
                    val x = (box.left - margin).coerceAtLeast(0)
                    val y = (box.top - margin).coerceAtLeast(0)
                    val w = (box.width() + 2 * margin).coerceAtMost(bitmap.width - x)
                    val h = (box.height() + 2 * margin).coerceAtMost(bitmap.height - y)
                    
                    val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                    onComplete(cropped)
                } else {
                    onComplete(null)
                }
            }
            .addOnFailureListener { onComplete(null) }
    }

    private fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        // Preprocess image (Resize to 112x112 -> Normalize)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 128.0f)) // Standard normalization for TFLite
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Run Inference
        val outputBuffer = ByteBuffer.allocateDirect(outputEmbeddingSize * 4) // 4 bytes per float
        outputBuffer.order(java.nio.ByteOrder.nativeOrder())
        
        // MobileFaceNet usually outputs [1, 192] array
        val output = Array(1) { FloatArray(outputEmbeddingSize) }
        interpreter?.run(tensorImage.buffer, output)
        
        return output[0]
    }

    /**
     * Calculates how parallel the two vectors are.
     * 1.0 = Same person
     * 0.7 - 0.8 = Threshold for "Match"
     * < 0.4 = Different person
     */
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        if (normA == 0f || normB == 0f) return 0f
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
}