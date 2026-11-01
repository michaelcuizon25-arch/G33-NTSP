package com.example.note2snap.ccl

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** Raw (unpadded) component geometry only. Never filters by OCR character or confidence. */
internal object SideBorderGeometry {
    fun isClearFragment(box: Rect, imageWidth: Int, imageHeight: Int, medianTextHeight: Int): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || box.isEmpty) return false
        val edgeBand = min(12, max(2, imageWidth / 100))
        val maxStrokeWidth = min(8, max(2, (imageWidth * 0.006f).toInt()))
        val minimumHeight = max(24, max(medianTextHeight.coerceAtLeast(1) * 3, imageHeight / 10))
        return (box.left <= edgeBand || box.right >= imageWidth - edgeBand) &&
            box.width() <= maxStrokeWidth && box.height() >= minimumHeight &&
            box.height().toLong() >= box.width().toLong() * 12
    }
}
