package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reference inference transform from the supplied SimpleHTR process_img (no augmentation).
 * No OpenCV Android dependency: reproduces its INTER_LINEAR affine sample grid and
 * BORDER_TRANSPARENT behavior, verified against Python/OpenCV golden tensors.
 */
internal class SimpleHtrReferencePreprocessor {
    data class Result(val dynamicWidth: Int, val input: ByteBuffer?)

    fun preprocess(bitmap: Bitmap): Result {
        val width = dynamicWidth(bitmap.width, bitmap.height)
        if (width > MODEL_WIDTH) return Result(width, null)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // OpenCV IMREAD_GRAYSCALE of unprofiled opaque RGB PNGs uses libpng's truncated
        // BT.601 coefficients. Grayscale PNGs pass through unchanged. Original
        // JPEG decoder luminance and PNG gamma/profile conversion cannot be
        // reconstructed exactly from a decoded RGB bitmap. Use preprocessGrayscale
        // for parity checks on identical grayscale input samples.
        val gray = ByteArray(pixels.size) { i ->
            val p = pixels[i]
            ((9797 * Color.red(p) + 19234 * Color.green(p) + 3737 * Color.blue(p)) shr 15).toByte()
        }
        return preprocessGrayscale(bitmap.width, bitmap.height, gray)
    }

    fun dynamicWidth(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val unrounded = (HEIGHT.toDouble() / height * width + PADDING).toInt()
        return unrounded + (4 - unrounded % 4) % 4
    }

    /** Row-major unsigned grayscale samples, as supplied to Python process_img. */
    fun preprocessGrayscale(width: Int, height: Int, gray: ByteArray): Result {
        require(width > 0 && height > 0 && gray.size.toLong() == width.toLong() * height)
        val targetWidth = dynamicWidth(width, height)
        // The notebook skips these. Return explicit incompatibility, never crop,
        // compress, or silently resize the model input to pretend reference parity.
        if (targetWidth > MODEL_WIDTH) return Result(targetWidth, null)

        val scale = HEIGHT.toDouble() / height
        val translation = (targetWidth - width * scale) / 2.0
        // Python creates np.float32 M, while OpenCV inverts M in double precision.
        val f = scale.toFloat().toDouble()
        val tx = translation.toFloat().toDouble()
        val inverseScale = f / (f * f)
        val inverseTranslation = -inverseScale * tx
        val xDelta = IntArray(targetWidth) { Math.rint(inverseScale * it * 1024).toInt() }
        val xOrigin = Math.rint(inverseTranslation * 1024).toInt() + 16
        val input = ByteBuffer.allocateDirect(MODEL_WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        for (x in 0 until MODEL_WIDTH) for (y in 0 until HEIGHT) {
            var sample = 255.0
            if (x < targetWidth) {
                // warpAffine uses 10-bit affine coordinates and 5-bit interpolation
                // fractions, with a half-step rounding offset for INTER_LINEAR.
                val fixedX = (xOrigin + xDelta[x]) shr 5
                val fixedY = (Math.rint(inverseScale * y * 1024).toInt() + 16) shr 5
                val sx = fixedX shr 5
                val sy = fixedY shr 5
                if (sx in 0 until width && sy in 0 until height) {
                    val fx = fixedX and 31
                    val fy = fixedY and 31
                    var sum = 0.0
                    var weightSum = 0
                    // BORDER_TRANSPARENT leaves out-of-bounds origins white; valid
                    // origins at the right/bottom edge renormalize available weights.
                    for (dy in 0..1) for (dx in 0..1) {
                        if (sx + dx < width && sy + dy < height) {
                            val weight = (if (dx == 0) 32 - fx else fx) *
                                (if (dy == 0) 32 - fy else fy)
                            sum += (gray[(sy + dy) * width + sx + dx].toInt() and 255) * weight
                            weightSum += weight
                        }
                    }
                    if (weightSum > 0) sample = sum / weightSum
                }
            }
            // Python performs interpolation and normalization in float64, then
            // casts to float32. Do not quantize intermediate samples to a bitmap.
            input.putFloat((sample / 255.0 - 0.5).toFloat())
        }
        input.rewind()
        return Result(targetWidth, input)
    }

    private companion object {
        const val MODEL_WIDTH = 512
        const val HEIGHT = 32
        const val PADDING = 16
    }
}
