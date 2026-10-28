
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

/**
 * Preprocesses one physical text line for the NOTE2SNAP CRNN model.
 *
 * Input dimensions:  [1, 512, 32]
 * Input data type:   FLOAT32
 *
 * Processing:
 * 1. Convert the image to grayscale.
 * 2. Resize while preserving the aspect ratio.
 * 3. Place the image on a white 512 x 32 canvas.
 * 4. Normalize pixels to the range 0.0 to 1.0.
 * 5. Write pixels in [512, 32, 1] order.
 *
 * IMPORTANT:
 * This normalization matches the supplied training pipeline's
 * 0.0–1.0 pixel range. Verify model accuracy against a reference
 * transcription after making this change.
 */
internal class CrnnInputPreprocessor {

    private val INPUT_WIDTH = 512
    private val INPUT_HEIGHT = 32

    /**
     * Preprocess one physical text line.
     */
    fun preprocess(bitmap: Bitmap): ByteBuffer {

        require(!bitmap.isRecycled) {
            "Cannot preprocess a recycled bitmap."
        }

        require(bitmap.width > 0 && bitmap.height > 0) {
            "Input bitmap must have valid dimensions."
        }

        // 1. Convert to grayscale.
        val grayBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )

        val grayscaleCanvas = Canvas(grayBitmap)

        val grayscalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                }
            )
        }

        grayscaleCanvas.drawBitmap(
            bitmap,
            0f,
            0f,
            grayscalePaint
        )

        // 2. Resize while preserving the aspect ratio.
        val scale = min(
            INPUT_WIDTH.toFloat() / grayBitmap.width,
            INPUT_HEIGHT.toFloat() / grayBitmap.height
        )

        val resizedWidth = max(
            1,
            (grayBitmap.width * scale).roundToInt()
        )

        val resizedHeight = max(
            1,
            (grayBitmap.height * scale).roundToInt()
        )

        val resizedBitmap = Bitmap.createScaledBitmap(
            grayBitmap,
            resizedWidth,
            resizedHeight,
            true
        )

        // 3. Create a white 512 x 32 canvas.
        val letterboxedBitmap = Bitmap.createBitmap(
            INPUT_WIDTH,
            INPUT_HEIGHT,
            Bitmap.Config.ARGB_8888
        )

        letterboxedBitmap.eraseColor(Color.WHITE)

        // Place the resized image at the top-left.
        // Remaining space is padded on the right and bottom.
        val letterboxCanvas = Canvas(letterboxedBitmap)

        letterboxCanvas.drawBitmap(
            resizedBitmap,
            0f,
            0f,
            null
        )

        // 4. Create FLOAT32 input buffer.
        val inputBuffer = ByteBuffer.allocateDirect(
            INPUT_WIDTH * INPUT_HEIGHT * 4
        ).order(ByteOrder.nativeOrder())

        // 5. Write pixels in [512, 32, 1] order.
        // x is the outer loop; y is the inner loop.
        for (x in 0 until INPUT_WIDTH) {
            for (y in 0 until INPUT_HEIGHT) {

                val pixel = letterboxedBitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Grayscale value normalized to [0.0, 1.0].
                val gray = (
                        0.299f * r +
                                0.587f * g +
                                0.114f * b
                        ) / 255f

                // Updated: do not subtract 0.5f.
                inputBuffer.putFloat(gray)
            }
        }

        inputBuffer.rewind()

        // Release temporary bitmaps when they are no longer needed.
        if (resizedBitmap !== grayBitmap) {
            resizedBitmap.recycle()
        }

        grayBitmap.recycle()
        letterboxedBitmap.recycle()

        return inputBuffer
    }
}
