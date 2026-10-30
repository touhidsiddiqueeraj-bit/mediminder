package com.mediminder.app.data.scanner

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mediminder.app.domain.service.LabelScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device OCR with ML Kit Text Recognition (Latin). The photo never leaves
 * the device and no network is used.
 */
@OptIn(ExperimentalGetImage::class)
@Singleton
class MlKitLabelScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : LabelScanner {

    override suspend fun recognizeText(photoUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, Uri.parse(photoUri))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text -> continuation.resumeWith(Result.success(text.text)) }
                    .addOnFailureListener { continuation.resumeWith(Result.success(null)) }
                continuation.invokeOnCancellation { recognizer.close() }
            }
            result?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
