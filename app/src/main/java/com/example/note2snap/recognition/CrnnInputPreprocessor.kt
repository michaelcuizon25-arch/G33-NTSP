package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Existing production transform, extracted unchanged for diagnostics and parity tests. */
internal class CrnnInputPreprocessor {
    private val INPUT_WIDTH = 512
    private val INPUT_HEIGHT = 32
    /**
     * Preprocess ONE physical text line.
     *
     * Epoch 3 implementation:
     *
     * grayscale
     * -> aspect-ratio preserving resize
     * -> 512 × 32 white canvas
     * -> pad right/bottom
     * -> Float32 normalization (gray / 255 - 0.5)
     * -> transpose to [512, 32, 1]
     *
     * Existing Android behavior, deliberately preserved for diagnosis.
     * The supplied Epoch 3 notebook uses dynamic-width height-first resizing,
     * centered 16-pixel margins and width rounding before right-padding to 512.
     * This transform is NOT an exact reproduction of that training transform.
     */
    fun preprocess(
        bitmap: Bitmap
    ): ByteBuffer {

        // 1. Convert to grayscale
        val grayBitmap =
            Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(grayBitmap)

        val paint =
            Paint()

        val colorMatrix =
            ColorMatrix().apply {
                setSaturation(0f)
            }

        paint.colorFilter =
            ColorMatrixColorFilter(
                colorMatrix
            )

        canvas.drawBitmap(
            bitmap,
            0f,
            0f,
            paint
        )

        // 2. Preserve aspect ratio
        val scale =
            min(
                INPUT_WIDTH.toFloat() /
                        grayBitmap.width,

                INPUT_HEIGHT.toFloat() /
                        grayBitmap.height
            )

        val resizedWidth =
            max(
                1,
                (
                        grayBitmap.width *
                                scale
                        ).roundToInt()
            )

        val resizedHeight =
            max(
                1,
                (
                        grayBitmap.height *
                                scale
                        ).roundToInt()
            )

        val resized =
            Bitmap.createScaledBitmap(
                grayBitmap,
                resizedWidth,
                resizedHeight,
                true
            )

        // 3. Create 512 × 32 white canvas
        val letterboxed =
            Bitmap.createBitmap(
                INPUT_WIDTH,
                INPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
            )

        letterboxed.eraseColor(
            Color.WHITE
        )

        /*
         * Existing Android placement (differs from the supplied notebook):
         *
         * resized image starts at:
         * x = 0
         * y = 0
         *
         * Remaining space is padded on
         * the right and bottom.
         */
        val letterboxCanvas =
            Canvas(letterboxed)

        letterboxCanvas.drawBitmap(
            resized,
            0f,
            0f,
            null
        )

        // 4. Create Float32 TFLite input
        val inputBuffer =
            ByteBuffer.allocateDirect(
                4 *
                        INPUT_WIDTH *
                        INPUT_HEIGHT
            ).order(
                ByteOrder.nativeOrder()
            )

        /*
         * Notebook:
         *
         * original:
         * [32, 512, 1]
         *
         * transpose:
         * [512, 32, 1]
         *
         * Therefore x is the outer loop
         * and y is the inner loop.
         */
        for (
        x in 0 until INPUT_WIDTH
        ) {

            for (
            y in 0 until INPUT_HEIGHT
            ) {

                val pixel =
                    letterboxed.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)

                val gray =
                    (
                            0.299f * r +
                                    0.587f * g +
                                    0.114f * b
                            ) / 255f

                inputBuffer.putFloat(
                    gray - 0.5f
                )
            }
        }

        inputBuffer.rewind()

        return inputBuffer
    }

}
