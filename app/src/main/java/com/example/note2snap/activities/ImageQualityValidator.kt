package com.example.note2snap.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class ImageQualityIssue {
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
    GLARE,
    BOARD_NOT_CLEAR
}

data class ImageQualityResult(
    val isValid: Boolean,
    val issue: ImageQualityIssue? = null,
    val title: String = "",
    val message: String = "",
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val edgeStrength: Double = 0.0
)

object ImageQualityValidator {

    /**
     * Conservative quality gate:
     * severe image-quality problems block OCR.
     *
     * Minor OCR uncertainty is handled downstream by Needs Review.
     */
    fun validate(
        file: File
    ): ImageQualityResult {

        val bitmap =
            decodeSampled(
                file,
                maxDimension = 900
            ) ?: return ImageQualityResult(
                isValid = false,
                issue = ImageQualityIssue.BOARD_NOT_CLEAR,
                title = "Image could not be checked",
                message =
                    "Note2Snap could not read this image. Please capture or choose another photo."
            )

        return try {
            analyze(
                bitmap
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun analyze(
        bitmap: Bitmap
    ): ImageQualityResult {

        val width = bitmap.width
        val height = bitmap.height

        if (width < 120 || height < 120) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "The image is too small to scan reliably. Move closer or choose a clearer photo."
            )
        }

        // Sampling keeps validation fast on mobile.
        val step = max(1, minOf(width, height) / 280)

        var count = 0L
        var sum = 0.0
        var sumSquares = 0.0
        var darkCount = 0L
        var brightCount = 0L
        var inkCount = 0L

        var maskedEdgeTotal = 0.0
        var maskedEdgeSamples = 0L

        // 4 x 4 grid used to detect localized specular glare.
        val gridSize = 4
        val cellGlare = LongArray(gridSize * gridSize)
        val cellCount = LongArray(gridSize * gridSize)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = luminance(pixel)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val saturation = maxC - minC

                count++
                sum += gray
                sumSquares += gray * gray

                if (gray < 40.0) {
                    darkCount++
                }

                if (gray > 245.0) {
                    brightCount++
                }

                // Ink heuristic: reasonably dark and not heavily saturated background surface
                if (gray < 165.0 && saturation < 100) {
                    inkCount++
                }

                val cellX = (x * gridSize / width).coerceIn(0, gridSize - 1)
                val cellY = (y * gridSize / height).coerceIn(0, gridSize - 1)
                val cell = cellY * gridSize + cellX

                cellCount[cell]++

                // Specular Glare check: extremely bright with near-zero saturation (pure white highlight blob)
                if (maxC > 242 && saturation < 28) {
                    cellGlare[cell]++
                }

                // MASKED EDGE STRENGTH:
                // Exclude clipped white background (gray > 240) and extreme shadows (gray < 25).
                // This prevents bright, clean whiteboards from artificially deflating edge strength.
                val isValidForEdge = gray in 25.0..240.0

                if (x + step < width) {
                    val rightPixel = bitmap.getPixel(x + step, y)
                    val rightGray = luminance(rightPixel)
                    if (isValidForEdge && rightGray in 25.0..240.0) {
                        maskedEdgeTotal += abs(gray - rightGray)
                        maskedEdgeSamples++
                    }
                }

                if (y + step < height) {
                    val downPixel = bitmap.getPixel(x, y + step)
                    val downGray = luminance(downPixel)
                    if (isValidForEdge && downGray in 25.0..240.0) {
                        maskedEdgeTotal += abs(gray - downGray)
                        maskedEdgeSamples++
                    }
                }

                x += step
            }
            y += step
        }

        if (count <= 0) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Note2Snap could not inspect this image. Please try another photo."
            )
        }

        val average = sum / count
        val variance = (sumSquares / count) - (average * average)
        val contrast = sqrt(variance.coerceAtLeast(0.0))

        val edgeStrength = if (maskedEdgeSamples > 0) {
            maskedEdgeTotal / maskedEdgeSamples
        } else {
            0.0
        }

        val darkRatio = darkCount.toDouble() / count
        val brightRatio = brightCount.toDouble() / count
        val inkRatio = inkCount.toDouble() / count

        var strongestGlareCell = 0.0
        cellCount.indices.forEach { index ->
            if (cellCount[index] > 0) {
                val ratio = cellGlare[index].toDouble() / cellCount[index]
                if (ratio > strongestGlareCell) {
                    strongestGlareCell = ratio
                }
            }
        }

        // 1. Severe low light check
        if (average < 52.0 || darkRatio > 0.58) {
            return invalid(
                ImageQualityIssue.TOO_DARK,
                "Lighting is too low",
                "Move to a brighter area or turn on the flash, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 2. Severe overall overexposure check (evaluated before blur!)
        if (average > 238.0 || brightRatio > 0.48) {
            return invalid(
                ImageQualityIssue.TOO_BRIGHT,
                "Image is too bright",
                "Reduce direct light or change your camera angle before scanning again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 3. Localized specular glare check
        if (strongestGlareCell > 0.35 && brightRatio < 0.45) {
            return invalid(
                ImageQualityIssue.GLARE,
                "Strong glare detected",
                "A bright reflection is covering part of the board. Change your angle or reduce direct light.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 4. Missing board or unreadable content check
        if (contrast < 11.0 || inkRatio < 0.0025) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Make sure the board and its writing are visible inside the frame, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 5. Masked Blur & Dirty Lens check
        if (edgeStrength < 4.2) {
            val isSmudgedLens = contrast in 12.0..28.0
            val message = if (isSmudgedLens) {
                "Clean your camera lens, hold your phone steady, and capture the board again."
            } else {
                "Hold your phone steady and capture the board again."
            }

            return invalid(
                ImageQualityIssue.BLURRY,
                "Image looks blurry",
                message,
                average,
                contrast,
                edgeStrength
            )
        }

        return ImageQualityResult(
            isValid = true,
            brightness = average,
            contrast = contrast,
            edgeStrength = edgeStrength
        )
    }

    private fun invalid(
        issue: ImageQualityIssue,
        title: String,
        message: String,
        brightness: Double = 0.0,
        contrast: Double = 0.0,
        edgeStrength: Double = 0.0
    ): ImageQualityResult {
        return ImageQualityResult(
            isValid = false,
            issue = issue,
            title = title,
            message = message,
            brightness = brightness,
            contrast = contrast,
            edgeStrength = edgeStrength
        )
    }

    private fun luminance(pixel: Int): Double {
        return Color.red(pixel) * 0.299 +
                Color.green(pixel) * 0.587 +
                Color.blue(pixel) * 0.114
    }

    private fun decodeSampled(
        file: File,
        maxDimension: Int
    ): Bitmap? {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}
