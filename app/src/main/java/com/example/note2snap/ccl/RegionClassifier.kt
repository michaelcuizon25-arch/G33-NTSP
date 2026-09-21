package com.example.note2snap.ccl

import android.graphics.Rect

object RegionClassifier {

    fun classify(
        box: Rect,
        inkPixels: Int,
        imageWidth: Int,
        imageHeight: Int
    ): RegionType? {

        val width = box.width()
        val height = box.height()

        if (width <= 0 || height <= 0) {
            return null
        }

        val area = width * height

        if (area < MIN_GLYPH_AREA) {
            return null
        }

        val widthRatio =
            width.toFloat() / imageWidth.coerceAtLeast(1)

        val heightRatio =
            height.toFloat() / imageHeight.coerceAtLeast(1)

        val extent =
            inkPixels.toFloat() / area.coerceAtLeast(1)

        /*
         * A genuine diagram/visual often occupies a meaningful
         * amount of BOTH width and height.
         *
         * Single handwritten characters may be large,
         * but normally do not occupy this much of the image
         * in both dimensions.
         */
        if (
            widthRatio >= MIN_VISUAL_WIDTH_RATIO &&
            heightRatio >= MIN_VISUAL_HEIGHT_RATIO &&
            extent <= MAX_VISUAL_EXTENT
        ) {
            return RegionType.NON_TEXT
        }

        return RegionType.TEXT
    }

    private const val MIN_GLYPH_AREA = 12

    private const val MIN_VISUAL_WIDTH_RATIO = 0.12f
    private const val MIN_VISUAL_HEIGHT_RATIO = 0.12f

    // Diagrams generally contain a lot of empty space
    // inside their bounding rectangle.
    private const val MAX_VISUAL_EXTENT = 0.35f
}