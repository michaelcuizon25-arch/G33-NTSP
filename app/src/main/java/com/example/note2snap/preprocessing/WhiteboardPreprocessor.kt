package com.example.note2snap.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Downsamples, converts to grayscale, and applies adaptive thresholding.
 * The binary output uses white foreground ink on a black background for CCL.
 */
class WhiteboardPreprocessor {
    fun process(source: Bitmap): PreprocessingResult {
        val recognitionBitmap = downscale(source, MAX_SOURCE_DIMENSION)
        val width = recognitionBitmap.width
        val height = recognitionBitmap.height
        val sourcePixels = IntArray(width * height)
        recognitionBitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        val gray = IntArray(sourcePixels.size)
        for (i in sourcePixels.indices) {
            val pixel = sourcePixels[i]
            gray[i] = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        }

        val integral = LongArray((width + 1) * (height + 1))
        for (y in 1..height) {
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += gray[(y - 1) * width + (x - 1)]
                integral[y * (width + 1) + x] = integral[(y - 1) * (width + 1) + x] + rowSum
            }
        }

        val binaryPixels = IntArray(gray.size)
        for (y in 0 until height) {
            val top = (y - WINDOW_RADIUS).coerceAtLeast(0)
            val bottom = (y + WINDOW_RADIUS).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - WINDOW_RADIUS).coerceAtLeast(0)
                val right = (x + WINDOW_RADIUS).coerceAtMost(width - 1)
                val area = (right - left + 1) * (bottom - top + 1)
                val sum = rectangleSum(integral, width + 1, left, top, right, bottom)
                val localMean = sum.toFloat() / area
                val isInk = gray[y * width + x] < localMean - THRESHOLD_OFFSET
                binaryPixels[y * width + x] = if (isInk) Color.WHITE else Color.BLACK
            }
        }

        removeIsolatedNoise(binaryPixels, width, height)
        clearImageBorder(
            pixels = binaryPixels,
            width = width,
            height = height
        )
        val binary = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binary.setPixels(binaryPixels, 0, width, 0, 0, width, height)

        return PreprocessingResult(binary, recognitionBitmap, width, height)
    }

    private fun clearImageBorder(
        pixels: IntArray,
        width: Int,
        height: Int
    ) {

        /*
         * Ignore a small outer strip of the photo.
         *
         * Whiteboard photos commonly contain:
         * - metal frames
         * - marker trays
         * - wall edges
         * - crop artifacts
         *
         * These are not note content.
         */
        val marginX =
            (width * 0.018f)
                .toInt()
                .coerceIn(
                    8,
                    30
                )

        val marginY =
            (height * 0.018f)
                .toInt()
                .coerceIn(
                    8,
                    30
                )

        for (y in 0 until height) {

            for (x in 0 until width) {

                val insideBorder =
                    x < marginX ||
                            x >= width - marginX ||
                            y < marginY ||
                            y >= height - marginY

                if (insideBorder) {
                    pixels[
                        y * width + x
                    ] = Color.BLACK
                }
            }
        }
    }

    private fun rectangleSum(
        integral: LongArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Long {
        val x1 = left
        val y1 = top
        val x2 = right + 1
        val y2 = bottom + 1
        return integral[y2 * stride + x2] - integral[y1 * stride + x2] -
                integral[y2 * stride + x1] + integral[y1 * stride + x1]
    }

    private fun removeIsolatedNoise(pixels: IntArray, width: Int, height: Int) {
        val original = pixels.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (original[index] != Color.WHITE) continue
                var neighbors = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if ((dx != 0 || dy != 0) && original[(y + dy) * width + x + dx] == Color.WHITE) {
                        neighbors++
                    }
                }
                if (neighbors < 2) pixels[index] = Color.BLACK
            }
        }
    }

    private fun downscale(bitmap: Bitmap, maximumSide: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maximumSide) return bitmap
        val scale = maximumSide.toFloat() / largestSide
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    }

    private companion object {
        const val MAX_SOURCE_DIMENSION = 1800
        const val WINDOW_RADIUS = 12
        const val THRESHOLD_OFFSET = 10f
    }
}
