package com.mediminder.app.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.mediminder.app.domain.service.OcrBox
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Pre-processes label photos before OCR so ML Kit sees clean, upright text:
 *
 * 1. EXIF orientation applied (photos from CameraX carry rotation metadata).
 * 2. Very large photos are down-sampled — ML Kit does not benefit from more
 *    than ~2000 px on the long edge, and it keeps memory low on old phones.
 * 3. Converted to grayscale with a histogram auto-contrast stretch (clips the
 *    darkest 1% and brightest 1%), which helps a lot with dim/uneven photos.
 *
 * Pure `android.graphics` — no third-party dependencies, works fully offline.
 */
@Singleton
class OcrImagePreprocessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Longest edge we keep before OCR. */
    val maxDimension: Int = 2000

    /** Decodes, rotates, down-samples and contrast-enhances the photo. */
    fun load(photoUri: String): Bitmap? {
        val uri = Uri.parse(photoUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val upright = applyExifRotation(uri, decoded)
        return enhanceContrast(upright)
    }

    /** Applies the deskew rotation and/or text-area crop for the retry pass. */
    fun corrected(source: Bitmap, rotateDegrees: Float, cropBox: OcrBox?): Bitmap {
        var output = source
        if (rotateDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotateDegrees) }
            output = Bitmap.createBitmap(output, 0, 0, output.width, output.height, matrix, true)
        }
        if (cropBox != null) {
            val left = cropBox.left.coerceIn(0, output.width - 2)
            val top = cropBox.top.coerceIn(0, output.height - 2)
            val width = cropBox.width.coerceIn(1, output.width - left)
            val height = cropBox.height.coerceIn(1, output.height - top)
            if (width > 0 && height > 0) {
                output = Bitmap.createBitmap(output, left, top, width, height)
            }
        }
        return output
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Grayscale + auto-contrast (percentile histogram stretch). */
    private fun enhanceContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val target = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val pixels = IntArray(width * height)
        target.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1) luminance histogram
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            histogram[luminance]++
        }

        // 2) percentile clip points (darkest 1%, brightest 1%)
        val total = pixels.size
        val lowCut = total / 100
        val highCut = total - lowCut
        var low = 0
        var high = 255
        var running = 0
        for (value in 0..255) {
            running += histogram[value]
            if (running >= lowCut) {
                low = value
                break
            }
        }
        running = 0
        for (value in 0..255) {
            running += histogram[value]
            if (running >= highCut) {
                high = value
                break
            }
        }
        // Flat or already high-contrast image: nothing useful to stretch.
        if (high - low < MIN_CONTRAST_RANGE) return source

        // 3) lookup table
        val lut = IntArray(256)
        val range = high - low
        for (value in 0..255) {
            val stretched = ((value - low) * 255 / range).coerceIn(0, 255)
            lut[value] = stretched
        }

        // 4) apply as gray pixels
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            val gray = lut[luminance]
            pixels[index] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        return target
    }

    private companion object {
        /** Skip the stretch when the image already has a wide tonal range. */
        const val MIN_CONTRAST_RANGE = 24
    }
}
