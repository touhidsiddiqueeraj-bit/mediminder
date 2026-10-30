package com.mediminder.app.domain.service

/**
 * Runs on-device OCR over a photo of a medicine label.
 * Implemented with ML Kit Text Recognition (no cloud, no network needed).
 */
interface LabelScanner {

    /** The recognized text, or null when recognition failed/produced nothing. */
    suspend fun recognizeText(photoUri: String): String?
}
