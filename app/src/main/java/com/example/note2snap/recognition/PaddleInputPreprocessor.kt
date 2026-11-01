package com.example.note2snap.recognition

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

internal data class PaddleInput(val buffer: ByteBuffer, val width: Int, val resizedWidth: Int) {
    val shape get() = longArrayOf(1, 3, 48, width.toLong())
}

/** Official SVTR_LCNet batch-one preprocessing: BGR, OpenCV linear resize, NCHW, right zero padding. */
internal class PaddleInputPreprocessor(
    private val resizeBgr: (Bitmap, Int) -> ByteArray = ::openCvResizeBgr
) {
    fun prepare(source: Bitmap): PaddleInput {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val geometry = geometry(source.width, source.height)
        val bgr = resizeBgr(source, geometry.second)
        require(bgr.size == geometry.second * HEIGHT * 3)
        val input = ByteBuffer.allocateDirect(3 * HEIGHT * geometry.first * 4).order(ByteOrder.nativeOrder())
        // Direct buffers are zero initialized; padding is 0 in normalized space (not white / +1).
        for (y in 0 until HEIGHT) for (x in 0 until geometry.second) for (c in 0..2) {
            val value = (bgr[(y * geometry.second + x) * 3 + c].toInt() and 255) / 255f
            input.putFloat(((c * HEIGHT + y) * geometry.first + x) * 4, (value - 0.5f) / 0.5f)
        }
        return PaddleInput(input, geometry.first, geometry.second)
    }

    companion object {
        const val HEIGHT = 48
        const val MAX_WIDTH = 3200
        fun geometry(width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val exactWidth = HEIGHT * (width.toDouble() / height)
            // Do not squeeze/crop arbitrarily long handwriting into the supported memory envelope.
            require(exactWidth <= MAX_WIDTH) { "Paddle line aspect ratio exceeds supported width $MAX_WIDTH" }
            val padded = (HEIGHT * maxOf(320.0 / HEIGHT, width.toDouble() / height)).toInt()
            return padded to minOf(padded, ceil(exactWidth).toInt().coerceAtLeast(1))
        }
    }
}

private object PaddleOpenCv { val available: Boolean by lazy { OpenCVLoader.initLocal() } }
private fun openCvResizeBgr(bitmap: Bitmap, width: Int): ByteArray {
    check(PaddleOpenCv.available) { "Bundled OpenCV runtime unavailable for Paddle preprocessing" }
    val rgba = Mat(); val bgr = Mat(); val resized = Mat()
    try {
        Utils.bitmapToMat(bitmap, rgba, true)
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.resize(bgr, resized, Size(width.toDouble(), 48.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        return ByteArray(width * 48 * 3).also { check(resized.get(0, 0, it) == it.size) }
    } finally { resized.release(); bgr.release(); rgba.release() }
}
