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
     * only severe image-quality problems block OCR.
     *
     * The goal is not to reject every imperfect photo.
     * Minor OCR uncertainty is still handled later by Needs Review.
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

        val width =
            bitmap.width

        val height =
            bitmap.height

        if (
            width < 120 ||
            height < 120
        ) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "The image is too small to scan reliably. Move closer or choose a clearer photo."
            )
        }

        // Sampling every few pixels keeps validation fast on mobile.
        val step =
            max(
                1,
                minOf(
                    width,
                    height
                ) / 280
            )

        var count =
            0L

        var sum =
            0.0

        var sumSquares =
            0.0

        var darkCount =
            0L

        var brightCount =
            0L

        var inkCount =
            0L

        var edgeTotal =
            0.0

        var edgeSamples =
            0L

        // 4 x 4 grid used to detect a concentrated overexposed hotspot.
        val gridSize =
            4

        val cellBright =
            LongArray(
                gridSize *
                    gridSize
            )

        val cellCount =
            LongArray(
                gridSize *
                    gridSize
            )

        var y =
            0

        while (
            y <
            height
        ) {
            var x =
                0

            while (
                x <
                width
            ) {
                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val gray =
                    luminance(
                        pixel
                    )

                count++
                sum +=
                    gray

                sumSquares +=
                    gray *
                        gray

                if (
                    gray <
                    45.0
                ) {
                    darkCount++
                }

                if (
                    gray >
                    247.0
                ) {
                    brightCount++
                }

                // Dark strokes / meaningful content.
                if (
                    gray <
                    185.0
                ) {
                    inkCount++
                }

                val cellX =
                    (
                        x *
                            gridSize /
                            width
                        ).coerceIn(
                        0,
                        gridSize - 1
                    )

                val cellY =
                    (
                        y *
                            gridSize /
                            height
                        ).coerceIn(
                        0,
                        gridSize - 1
                    )

                val cell =
                    cellY *
                        gridSize +
                        cellX

                cellCount[cell]++

                if (
                    gray >
                    247.0
                ) {
                    cellBright[cell]++
                }

                if (
                    x + step <
                    width
                ) {
                    val right =
                        luminance(
                            bitmap.getPixel(
                                x + step,
                                y
                            )
                        )

                    edgeTotal +=
                        abs(
                            gray -
                                right
                        )

                    edgeSamples++
                }

                if (
                    y + step <
                    height
                ) {
                    val down =
                        luminance(
                            bitmap.getPixel(
                                x,
                                y + step
                            )
                        )

                    edgeTotal +=
                        abs(
                            gray -
                                down
                        )

                    edgeSamples++
                }

                x +=
                    step
            }

            y +=
                step
        }

        if (
            count <=
            0
        ) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Note2Snap could not inspect this image. Please try another photo."
            )
        }

        val average =
            sum /
                count

        val variance =
            (
                sumSquares /
                    count
                ) -
                (
                    average *
                        average
                    )

        val contrast =
            sqrt(
                variance.coerceAtLeast(
                    0.0
                )
            )

        val edgeStrength =
            if (
                edgeSamples >
                0
            ) {
                edgeTotal /
                    edgeSamples
            } else {
                0.0
            }

        val darkRatio =
            darkCount.toDouble() /
                count

        val brightRatio =
            brightCount.toDouble() /
                count

        val inkRatio =
            inkCount.toDouble() /
                count

        var strongestBrightCell =
            0.0

        cellCount.indices.forEach {
                index ->

            if (
                cellCount[index] >
                0
            ) {
                val ratio =
                    cellBright[index]
                        .toDouble() /
                        cellCount[index]

                if (
                    ratio >
                    strongestBrightCell
                ) {
                    strongestBrightCell =
                        ratio
                }
            }
        }

        // Severe darkness.
        if (
            average <
                58.0 ||
            darkRatio >
                0.62
        ) {
            return invalid(
                ImageQualityIssue.TOO_DARK,
                "Lighting is too low",
                "Move to a brighter area or turn on the flash, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // Severe overall overexposure.
        if (
            average >
                244.0 &&
            brightRatio >
                0.72
        ) {
            return invalid(
                ImageQualityIssue.TOO_BRIGHT,
                "Image is too bright",
                "Reduce direct light or change your camera angle before scanning again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // Localized white hotspot: likely reflection/glare.
        if (
            strongestBrightCell >
                0.78 &&
            brightRatio >
                0.08 &&
            brightRatio <
                0.62 &&
            contrast >
                22.0
        ) {
            return invalid(
                ImageQualityIssue.GLARE,
                "Strong glare detected",
                "A bright reflection is covering part of the board. Change your angle or reduce direct light.",
                average,
                contrast,
                edgeStrength
            )
        }

        // Very little useful contrast/content.
        if (
            contrast <
                11.5 ||
            inkRatio <
                0.004
        ) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Make sure the board and its writing are visible inside the frame, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // Blur check is deliberately conservative to avoid rejecting slightly soft photos.
        if (
            edgeStrength <
                4.8 &&
            contrast <
                34.0
        ) {
            return invalid(
                ImageQualityIssue.BLURRY,
                "Image looks blurry",
                "Clean the camera lens, hold your phone steady, and capture the board again.",
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

    private fun luminance(
        pixel: Int
    ): Double {
        return (
            Color.red(pixel) *
                0.299 +
            Color.green(pixel) *
                0.587 +
            Color.blue(pixel) *
                0.114
            )
    }

    private fun decodeSampled(
        file: File,
        maxDimension: Int
    ): Bitmap? {

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds =
                    true
            }

        BitmapFactory.decodeFile(
            file.absolutePath,
            bounds
        )

        if (
            bounds.outWidth <=
                0 ||
            bounds.outHeight <=
                0
        ) {
            return null
        }

        var sampleSize =
            1

        while (
            bounds.outWidth /
                sampleSize >
                maxDimension ||
            bounds.outHeight /
                sampleSize >
                maxDimension
        ) {
            sampleSize *=
                2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize =
                    sampleSize

                inPreferredConfig =
                    Bitmap.Config.ARGB_8888
            }
        )
    }
}
