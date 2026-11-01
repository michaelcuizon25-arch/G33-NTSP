package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color

/** Primary-mode-only negative checks, in addition to existing CCL TEXT classification/segmentation.
 * No model confidence threshold. Ambiguous handwriting proceeds to recognition.
 * This cannot prove semantic validity of all diagrams or arbitrary camera noise.
 */
internal object PaddlePrimaryInputGuard {
    fun rejectionReason(bitmap: Bitmap): String? {
        if (bitmap.isRecycled) return "invalid_crop"
        val width = bitmap.width; val height = bitmap.height
        if (width < 2 || height < 2) return "invalid_crop"
        val gray = IntArray(width * height)
        bitmap.getPixels(gray, 0, width, 0, 0, width, height)
        var low = 255; var high = 0
        for (i in gray.indices) {
            val p = gray[i]
            val value = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            gray[i] = value; low = minOf(low, value); high = maxOf(high, value)
        }
        if (high - low <= 1) return "uniform_blank"
        val threshold = low + (high - low) / 2
        var ink = 0; var left = width; var right = -1; var top = height; var bottom = -1
        val rows = IntArray(height); val columns = IntArray(width)
        for (y in 0 until height) for (x in 0 until width) if (gray[y * width + x] <= threshold) {
            ink++; rows[y]++; columns[x]++
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        if (ink < 6) return "isolated_mark"
        val inkWidth = right - left + 1; val inkHeight = bottom - top + 1
        // A vertical stem alone is ambiguous (I/l/1); CCL has global side-edge geometry.
        if (inkHeight <= 2 && inkWidth > inkHeight * 15)
            return "thin_separator"
        // Low-amplitude noise spread over almost every row/column, not localized faint strokes.
        if (high - low <= 12 && ink.toDouble() / gray.size > 0.25 &&
            rows.count { it > 0 } > height * 0.95 && columns.count { it > 0 } > width * 0.95)
            return "uniform_low_contrast_noise"
        if (inkWidth >= 48 && inkHeight >= 12 && inkWidth >= 2 * inkHeight) {
            var edgeInk = 0
            for (y in top..bottom) for (x in left..right) if (gray[y * width + x] <= threshold &&
                (x - left <= 1 || right - x <= 1 || y - top <= 1 || bottom - y <= 1)) edgeInk++
            val perimeter = 2 * inkWidth + 2 * inkHeight - 4
            if (edgeInk.toDouble() / ink > 0.90 && edgeInk >= perimeter * 0.80)
                return "empty_frame"
        }
        return null
    }
}
