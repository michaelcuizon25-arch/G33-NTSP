package com.example.note2snap.preprocessing

import android.graphics.Bitmap
import android.util.Log
import com.example.note2snap.BuildConfig
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage
import com.example.note2snap.diagnostics.PerfMetric
import kotlinx.coroutines.CancellationException
import kotlin.math.max

internal data class PreparedWhiteboardImage(
    val original: Bitmap, val recognition: Bitmap, val requested: Boolean,
    val applied: Boolean, val reason: String?, val elapsedMs: Long
)

/** Same Stage 1 pixels, without creating a binary mask when only ML Kit needs color. */
internal class WhiteboardImagePreparation(
    private val enableOpenCvEnhancement: Boolean = BuildConfig.ENABLE_OPENCV_ENHANCEMENT,
    private val enhancer: WhiteboardEnhancer = OpenCvWhiteboardEnhancer()
) {
    fun prepare(source: Bitmap): PreparedWhiteboardImage {
        val original = PerformanceDiagnostics.measure(PerfStage.IMAGE_RESIZE) { downscale(source, MAX_SOURCE_DIMENSION) }
        val start = System.nanoTime()
        val enhancement = if (!enableOpenCvEnhancement) WhiteboardEnhancement(original, false, "disabled") else try {
            PerformanceDiagnostics.measure(PerfStage.OPENCV) { enhancer.enhance(original) }.also {
                require(!it.bitmap.isRecycled && it.bitmap.width == original.width && it.bitmap.height == original.height) {
                    "Enhancement must preserve the CCL/recognition coordinate space"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("NOTE2SNAP_PIPELINE", "OpenCV enhancement failed; using original", error)
            WhiteboardEnhancement(original, false, error.javaClass.simpleName)
        } catch (error: LinkageError) {
            Log.w("NOTE2SNAP_PIPELINE", "OpenCV native library unavailable; using original", error)
            WhiteboardEnhancement(original, false, "native_library_unavailable")
        }
        val elapsedMs = if (enableOpenCvEnhancement) (System.nanoTime() - start) / 1_000_000 else 0
        val recognitionBitmap = enhancement.bitmap
        Log.i("NOTE2SNAP_PIPELINE", "OpenCV requested=$enableOpenCvEnhancement applied=${enhancement.applied} " +
            "reason=${enhancement.reason} elapsedMs=$elapsedMs")
        PerformanceDiagnostics.metric(PerfMetric.OPENCV_REQUESTED, if (enableOpenCvEnhancement) 1 else 0)
        PerformanceDiagnostics.metric(PerfMetric.OPENCV_APPLIED, if (enhancement.applied) 1 else 0)
        PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_WIDTH, recognitionBitmap.width.toLong())
        PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_HEIGHT, recognitionBitmap.height.toLong())
        return PreparedWhiteboardImage(original, recognitionBitmap, enableOpenCvEnhancement,
            enhancement.applied, enhancement.reason, elapsedMs)
    }

    private fun downscale(bitmap: Bitmap, maximumSide: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maximumSide) return bitmap
        val scale = maximumSide.toFloat() / largestSide
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    }

    private companion object { const val MAX_SOURCE_DIMENSION = 1800 }
}
