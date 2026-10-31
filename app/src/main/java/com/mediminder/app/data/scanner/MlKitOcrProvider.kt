package com.mediminder.app.data.scanner

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mediminder.app.domain.service.OcrBox
import com.mediminder.app.domain.service.OcrGeometry
import com.mediminder.app.domain.service.OcrLine
import com.mediminder.app.domain.service.OcrProvider
import com.mediminder.app.domain.service.OcrResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR with ML Kit Text Recognition v2 (Latin, bundled model).
 *
 * The photo is pre-processed (down-sampled, upright, contrast-stretched). If
 * the first pass suggests the photo is skewed or the text only covers a small
 * part of it, recognition runs once more on a corrected bitmap and the better
 * of the two results is kept. Everything happens on the phone.
 */
@Singleton
class MlKitOcrProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preprocessor: OcrImagePreprocessor,
) : OcrProvider {

    override suspend fun recognize(photoUri: String): OcrResult = withContext(Dispatchers.IO) {
        val bitmap = try {
            preprocessor.load(photoUri)
        } catch (e: Exception) {
            null
        } ?: return@withContext OcrResult()

        val firstPass = try {
            recognizeOnce(bitmap)
        } catch (e: Exception) {
            emptyList()
        }
        if (firstPass.isEmpty()) return@withContext OcrResult()

        val correction = OcrGeometry.computeCorrection(firstPass, bitmap.width, bitmap.height)
        if (correction == null) {
            return@withContext OcrResult(lines = firstPass)
        }

        val secondPass = try {
            val correctedBitmap = preprocessor.corrected(
                bitmap,
                correction.rotateDegrees,
                correction.cropBox,
            )
            recognizeOnce(correctedBitmap)
        } catch (e: Exception) {
            emptyList()
        }
        // Keep whichever pass read more text; a wrong correction never hurts.
        val best = if (secondPass.sumOf { it.text.trim().length } >=
            firstPass.sumOf { it.text.trim().length }
        ) {
            secondPass
        } else {
            firstPass
        }
        OcrResult(lines = best, corrections = listOf(correction.description))
    }

    private suspend fun recognizeOnce(bitmap: Bitmap): List<OcrLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> continuation.resume(result) }
                    .addOnFailureListener { e -> continuation.resumeWithException(e) }
                continuation.invokeOnCancellation { runCatching { recognizer.close() } }
            }
        } finally {
            runCatching { recognizer.close() } // closes exactly once on every path
        }
        return text.textBlocks
            .flatMap { block -> block.lines }
            .map { line -> line.toOcrLine() }
    }

    private fun Text.Line.toOcrLine(): OcrLine = OcrLine(
        text = text,
        box = boundingBox?.let { box ->
            OcrBox(left = box.left, top = box.top, right = box.right, bottom = box.bottom)
        },
        confidence = confidence.takeIf { it > 0f },
        angleDegrees = angle,
    )
}
