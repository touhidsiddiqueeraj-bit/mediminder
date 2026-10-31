package com.mediminder.app.domain.service

/**
 * On-device OCR over a photo of a medicine label, box or prescription.
 *
 * Implementations must run entirely on the phone: the photo never leaves the
 * device and no network is used. Unlike a plain-text scanner this returns
 * line-level results (text + bounding box + confidence) so the prescription
 * extractor can tell medication lines from doctor names, addresses and dates.
 */

/** Axis-aligned bounding box of a recognized line, in image pixel space. */
data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One recognized text line. */
data class OcrLine(
    val text: String,
    val box: OcrBox? = null,
    /** 0..1 when the engine reports one; null when unknown. */
    val confidence: Float? = null,
    /** Clockwise rotation of the line in degrees, when the engine reports it. */
    val angleDegrees: Float = 0f,
)

/** Full recognition result of one photo. */
data class OcrResult(
    val lines: List<OcrLine> = emptyList(),
    /** Pre-processing corrections that were applied (diagnostics only). */
    val corrections: List<String> = emptyList(),
) {
    val fullText: String get() = lines.joinToString("\n") { it.text }

    val hasText: Boolean get() = lines.any { it.text.isNotBlank() }

    val totalCharCount: Int get() = lines.sumOf { it.text.trim().length }
}

interface OcrProvider {

    /** Recognizes text in the photo. Never throws: failures give empty results. */
    suspend fun recognize(photoUri: String): OcrResult
}

/**
 * Pure geometry helper for the optional second OCR pass: decides whether the
 * photo should be deskewed (systematic line rotation) and/or cropped to the
 * text area before recognizing again.
 */
object OcrGeometry {

    /** Apply a correction when the median line angle is at least this. */
    const val MIN_SKEW_DEGREES = 2f

    /** Crop to the text area when it covers less than this share of the photo. */
    const val MAX_TEXT_AREA_SHARE = 0.70f

    data class Correction(
        val rotateDegrees: Float,
        val cropBox: OcrBox?,
        val description: String,
    )

    /**
     * @param lines recognized lines of the first pass (need boxes and angles).
     * @param imageWidth bitmap width of the first pass.
     * @param imageHeight bitmap height of the first pass.
     */
    fun computeCorrection(
        lines: List<OcrLine>,
        imageWidth: Int,
        imageHeight: Int,
    ): Correction? {
        if (imageWidth <= 0 || imageHeight <= 0) return null

        val rotatable = lines.filter { it.text.trim().length >= 4 && it.angleDegrees != 0f }
        val rotateDegrees = rotatable
            .map { it.angleDegrees }
            .sorted()
            .let { if (it.isEmpty()) 0f else it[it.size / 2] } // median
            .takeIf { kotlin.math.abs(it) >= MIN_SKEW_DEGREES } ?: 0f

        var cropBox: OcrBox? = null
        val boxes = lines.mapNotNull { it.box }.filter { it.width > 0 && it.height > 0 }
        if (boxes.isNotEmpty()) {
            val union = boxes.reduce { acc, box ->
                OcrBox(
                    left = minOf(acc.left, box.left),
                    top = minOf(acc.top, box.top),
                    right = maxOf(acc.right, box.right),
                    bottom = maxOf(acc.bottom, box.bottom),
                )
            }
            val textArea = union.width.toFloat() * union.height.toFloat()
            val imageArea = imageWidth.toFloat() * imageHeight.toFloat()
            if (textArea < MAX_TEXT_AREA_SHARE * imageArea) {
                // Add a small margin so edge characters are not cut off.
                val marginX = (imageWidth * 0.02f).toInt().coerceAtLeast(8)
                val marginY = (imageHeight * 0.02f).toInt().coerceAtLeast(8)
                cropBox = OcrBox(
                    left = (union.left - marginX).coerceAtLeast(0),
                    top = (union.top - marginY).coerceAtLeast(0),
                    right = (union.right + marginX).coerceAtMost(imageWidth),
                    bottom = (union.bottom + marginY).coerceAtMost(imageHeight),
                ).takeIf { it.width >= 32 && it.height >= 32 }
            }
        }

        val parts = buildList {
            if (rotateDegrees != 0f) add("deskew ${-rotateDegrees}deg")
            if (cropBox != null) add("crop to text area")
        }
        if (parts.isEmpty()) return null
        return Correction(
            rotateDegrees = -rotateDegrees, // counter the measured skew
            cropBox = cropBox,
            description = parts.joinToString(", "),
        )
    }
}
