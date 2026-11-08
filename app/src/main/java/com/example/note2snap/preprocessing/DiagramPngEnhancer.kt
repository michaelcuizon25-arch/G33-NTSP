package com.example.note2snap.preprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.max

/**
 * Enhances cropped whiteboard diagram region bitmaps before exporting/transferring to PNG.
 * - Cleans noisy gray background pixels to clean white (255, 255, 255).
 * - Sharpens dark/colored ink strokes for vivid diagrams.
 * - Adds clean white padding around the cropped bounding box to protect diagram edges.
 */
object DiagramPngEnhancer {

    fun enhanceDiagramCrop(bitmap: Bitmap, padding: Int = 12): Bitmap {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Calculate average background brightness near the crop border.
        var borderSum = 0L
        var borderCount = 0
        for (x in 0 until width) {
            val pTop = pixels[x]
            val pBottom = pixels[(height - 1) * width + x]
            borderSum += (Color.red(pTop) + Color.green(pTop) + Color.blue(pTop)) / 3
            borderSum += (Color.red(pBottom) + Color.green(pBottom) + Color.blue(pBottom)) / 3
            borderCount += 2
        }
        for (y in 0 until height) {
            val pLeft = pixels[y * width]
            val pRight = pixels[y * width + (width - 1)]
            borderSum += (Color.red(pLeft) + Color.green(pLeft) + Color.blue(pLeft)) / 3
            borderSum += (Color.red(pRight) + Color.green(pRight) + Color.blue(pRight)) / 3
            borderCount += 2
        }
        val bgAvg = if (borderCount > 0) (borderSum / borderCount).toInt() else 200
        val bgThreshold = max(bgAvg - 25, 170)

        // Enhance pixels: clean background to pure white, increase ink contrast.
        val enhancedPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luma = (r * 299 + g * 587 + b * 114) / 1000

            if (luma >= bgThreshold) {
                enhancedPixels[i] = Color.WHITE
            } else {
                val factor = 0.85f
                val newR = (r * factor).toInt().coerceIn(0, 255)
                val newG = (g * factor).toInt().coerceIn(0, 255)
                val newB = (b * factor).toInt().coerceIn(0, 255)
                enhancedPixels[i] = Color.rgb(newR, newG, newB)
            }
        }

        val cleanedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cleanedBitmap.setPixels(enhancedPixels, 0, width, 0, 0, width, height)

        if (padding <= 0) return cleanedBitmap

        val paddedWidth = width + padding * 2
        val paddedHeight = height + padding * 2
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888)
        paddedBitmap.eraseColor(Color.WHITE)

        val canvas = Canvas(paddedBitmap)
        canvas.drawBitmap(cleanedBitmap, padding.toFloat(), padding.toFloat(), null)
        cleanedBitmap.recycle()

        return paddedBitmap
    }
}
