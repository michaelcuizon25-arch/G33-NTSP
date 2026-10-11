package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import kotlin.math.max
import kotlin.math.min

internal data class PhysicalLine(val bitmap: Bitmap, val boundingBox: Rect)

/** Image-only line segmentation shared by production CRNN and regression tests. */
internal class PhysicalLineSegmenter {
    /**
     * Split a CCL text region into actual
     * physical handwritten lines.
     *
     * Uses a horizontal ink projection:
     *
     * 1. Convert pixels to grayscale.
     * 2. Estimate foreground threshold.
     * 3. Count dark pixels on each row.
     * 4. Detect continuous row groups.
     * 5. Merge very small vertical gaps.
     * 6. Crop each resulting text line.
     */
    fun splitIntoPhysicalLines(
        region: Region
    ): List<PhysicalLine> {

        val bitmap =
            region.croppedBitmap

        val width =
            bitmap.width

        val height =
            bitmap.height

        if (
            width <= 2 ||
            height <= 2
        ) {

            return emptyList()
        }

        /*
         * Calculate a grayscale threshold
         * using Otsu's method.
         *
         * Whiteboard:
         * background = bright
         * writing = dark
         */
        val threshold =
            calculateOtsuThreshold(
                bitmap
            )

        /*
         * Count foreground / ink pixels
         * for every horizontal row.
         */
        val rowInkCounts =
            IntArray(height)

        for (y in 0 until height) {

            var inkCount = 0

            for (x in 0 until width) {

                val gray =
                    grayscale(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

                if (gray <= threshold) {
                    inkCount++
                }
            }

            rowInkCounts[y] =
                inkCount
        }

        /*
         * Require a tiny amount of ink
         * before considering the row active.
         *
         * This suppresses isolated noise pixels.
         */
        val minimumInkPerRow =
            max(
                2,
                (width * 0.004f).toInt()
            )

        val activeRows =
            BooleanArray(height)

        for (y in 0 until height) {

            activeRows[y] =
                rowInkCounts[y] >=
                        minimumInkPerRow
        }

        /*
         * Convert active rows into vertical runs.
         */
        val rawRuns =
            mutableListOf<IntRange>()

        var runStart = -1

        for (y in 0 until height) {

            if (activeRows[y]) {

                if (runStart == -1) {
                    runStart = y
                }

            } else {

                if (runStart != -1) {

                    rawRuns.add(
                        runStart..(y - 1)
                    )

                    runStart = -1
                }
            }
        }

        if (runStart != -1) {

            rawRuns.add(
                runStart..(height - 1)
            )
        }

        /*
         * No supported ink rows: do not send an empty crop to the CRNN.
         */
        if (rawRuns.isEmpty()) {

            return emptyList()
        }

        /*
         * Merge tiny gaps caused by letters such as:
         *
         * i
         * j
         * :
         *
         * or broken handwriting strokes.
         */
        val gapTolerance =
            max(
                2,
                (rawRuns.maxOf { it.last - it.first + 1 } * 0.25f).toInt()
            )

        val mergedRuns =
            mutableListOf<IntRange>()

        var currentStart =
            rawRuns.first().first

        var currentEnd =
            rawRuns.first().last

        for (
        index in 1 until rawRuns.size
        ) {

            val next =
                rawRuns[index]

            val gap =
                next.first -
                        currentEnd -
                        1

            val currentHeight = currentEnd - currentStart + 1
            val nextHeight = next.last - next.first + 1
            val isDetachedSmallRun = min(currentHeight, nextHeight) <=
                    max(currentHeight, nextHeight) / 2
            if (gap <= 2 || (gap <= gapTolerance && isDetachedSmallRun)) {

                currentEnd =
                    next.last

            } else {

                mergedRuns.add(
                    currentStart..
                            currentEnd
                )

                currentStart =
                    next.first

                currentEnd =
                    next.last
            }
        }

        mergedRuns.add(
            currentStart..
                    currentEnd
        )

        /*
         * Remove extremely tiny vertical runs.
         *
         * These are usually dots/noise that were
         * not attached to a real text line.
         */
        val minimumLineHeight =
            max(
                3,
                (height * 0.025f).toInt()
            )

        val candidateRuns =
            mergedRuns.filter {

                val runHeight =
                    it.last -
                            it.first +
                            1

                runHeight >=
                        minimumLineHeight
            }

        /*
         * Unsupported runs are noise, not a fallback recognition input.
         */
        if (candidateRuns.isEmpty()) {

            return emptyList()
        }

        /*
         * Create individual line crops.
         */
        val lines =
            mutableListOf<PhysicalLine>()

        for ((index, run) in candidateRuns.withIndex()) {
            // Allocate whitespace between rows once so padding cannot duplicate
            // a neighboring line's ink in two CRNN inputs.
            val previous = candidateRuns.getOrNull(index - 1)
            val next = candidateRuns.getOrNull(index + 1)
            val cropTopLimit = previous?.let { (it.last + run.first + 1) / 2 } ?: 0
            val cropBottomLimit = next?.let { (run.last + it.first + 1) / 2 - 1 } ?: (height - 1)

            /*
             * Small vertical padding protects
             * ascenders/descenders from clipping.
             */
            val runHeight =
                run.last -
                        run.first +
                        1

            val verticalPadding =
                max(
                    2,
                    (runHeight * 0.20f)
                        .toInt()
                )

            val top =
                max(
                    cropTopLimit,
                    run.first -
                            verticalPadding
                )

            val bottom =
                min(
                    cropBottomLimit,
                    run.last +
                            verticalPadding
                )

            /*
             * Find actual left/right ink bounds
             * inside this line.
             */
            var minX =
                width

            var maxX =
                -1

            for (y in top..bottom) {

                for (x in 0 until width) {

                    val gray =
                        grayscale(
                            bitmap.getPixel(
                                x,
                                y
                            )
                        )

                    if (gray <= threshold) {

                        if (x < minX) {
                            minX = x
                        }

                        if (x > maxX) {
                            maxX = x
                        }
                    }
                }
            }

            /*
             * If something unusual happened,
             * use the complete horizontal width.
             */
            if (
                minX > maxX ||
                maxX < 0
            ) {

                minX = 0
                maxX = width - 1
            }

            val horizontalPadding =
                max(
                    4,
                    (
                            (maxX - minX + 1) *
                                    0.03f
                            ).toInt()
                )

            val left =
                max(
                    0,
                    minX -
                            horizontalPadding
                )

            val right =
                min(
                    width - 1,
                    maxX +
                            horizontalPadding
                )

            val cropWidth =
                right -
                        left +
                        1

            val cropHeight =
                bottom -
                        top +
                        1

            if (
                cropWidth <= 2 ||
                cropHeight <= 2
            ) {
                continue
            }

            val lineBitmap =
                Bitmap.createBitmap(
                    bitmap,
                    left,
                    top,
                    cropWidth,
                    cropHeight
                )

            /*
             * Convert local crop coordinates
             * back into global whiteboard coordinates.
             */
            val globalBox =
                Rect(
                    region.boundingBox.left +
                            left,

                    region.boundingBox.top +
                            top,

                    region.boundingBox.left +
                            right +
                            1,

                    region.boundingBox.top +
                            bottom +
                            1
                )

            lines.add(
                PhysicalLine(
                    bitmap =
                        lineBitmap,

                    boundingBox =
                        globalBox
                )
            )
        }

        /*
         * Return only line crops with detected ink support.
         */
        if (lines.isEmpty()) {

            return emptyList()
        }

        /*
         * Physical reading order inside
         * this region = top to bottom.
         */
        return lines.sortedBy {
            it.boundingBox.top
        }
    }

    /**
     * Otsu grayscale threshold.
     *
     * Helps distinguish dark handwriting
     * from bright whiteboard background.
     */
    private fun calculateOtsuThreshold(
        bitmap: Bitmap
    ): Int {

        val histogram =
            IntArray(256)

        val width =
            bitmap.width

        val height =
            bitmap.height

        var totalPixels = 0

        /*
         * Sampling every pixel is okay for
         * normal CCL text crops.
         */
        for (y in 0 until height) {

            for (x in 0 until width) {

                val gray =
                    grayscale(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

                histogram[gray]++

                totalPixels++
            }
        }

        var totalWeighted =
            0.0

        for (i in 0..255) {

            totalWeighted +=
                i.toDouble() *
                        histogram[i]
        }

        var backgroundWeight = 0
        var backgroundSum = 0.0

        var maximumVariance = -1.0

        var bestThreshold = 128

        for (threshold in 0..255) {

            backgroundWeight +=
                histogram[threshold]

            if (backgroundWeight == 0) {
                continue
            }

            val foregroundWeight =
                totalPixels -
                        backgroundWeight

            if (foregroundWeight == 0) {
                break
            }

            backgroundSum +=
                threshold.toDouble() *
                        histogram[threshold]

            val backgroundMean =
                backgroundSum /
                        backgroundWeight

            val foregroundMean =
                (
                        totalWeighted -
                                backgroundSum
                        ) /
                        foregroundWeight

            val difference =
                backgroundMean -
                        foregroundMean

            val variance =
                backgroundWeight
                    .toDouble() *
                        foregroundWeight
                            .toDouble() *
                        difference *
                        difference

            if (variance > maximumVariance) {

                maximumVariance =
                    variance

                bestThreshold =
                    threshold
            }
        }

        /*
         * Avoid overly dark threshold
         * on very clean white backgrounds.
         */
        return bestThreshold
            .coerceIn(
                60,
                230
            )
    }

    /**
     * RGB -> grayscale.
     *
     * Matches the conceptual grayscale
     * conversion used before CRNN input.
     */
    private fun grayscale(
        pixel: Int
    ): Int {

        val r =
            Color.red(pixel)

        val g =
            Color.green(pixel)

        val b =
            Color.blue(pixel)

        return (
                0.299f * r +
                        0.587f * g +
                        0.114f * b
                )
            .toInt()
            .coerceIn(
                0,
                255
            )
    }

}
