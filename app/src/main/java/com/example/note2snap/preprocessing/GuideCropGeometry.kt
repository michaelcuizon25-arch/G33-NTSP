package com.example.note2snap.preprocessing

import android.graphics.Rect

/** Immutable UI snapshot; no View references may cross into camera preparation IO. */
internal data class GuideCropGeometry(
    val previewWidth: Int, val previewHeight: Int,
    val left: Int, val top: Int, val width: Int, val height: Int
) {
    fun bounds(imageWidth: Int, imageHeight: Int): Rect {
        require(previewWidth > 0 && previewHeight > 0 && width > 0 && height > 0)
        val scale = maxOf(previewWidth.toFloat() / imageWidth, previewHeight.toFloat() / imageHeight)
        val overflowX = (imageWidth * scale - previewWidth) / 2f
        val overflowY = (imageHeight * scale - previewHeight) / 2f
        val x1 = ((left + overflowX) / scale).toInt().coerceIn(0, imageWidth - 1)
        val y1 = ((top + overflowY) / scale).toInt().coerceIn(0, imageHeight - 1)
        val x2 = ((left + width + overflowX) / scale).toInt().coerceIn(x1 + 1, imageWidth)
        val y2 = ((top + height + overflowY) / scale).toInt().coerceIn(y1 + 1, imageHeight)
        return Rect(x1, y1, x2, y2)
    }
}
