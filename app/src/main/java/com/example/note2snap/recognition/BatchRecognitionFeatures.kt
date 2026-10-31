package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.note2snap.BuildConfig
import com.example.note2snap.correction.CorrectionResult
import com.example.note2snap.correction.OfflineTextCorrector
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage
import com.example.note2snap.diagnostics.PerfMetric
import com.example.note2snap.preprocessing.OpenCvWhiteboardEnhancer
import com.example.note2snap.preprocessing.WhiteboardEnhancer
import com.example.note2snap.preprocessing.WhiteboardImagePreparation
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor

/** Coordinates refer to ML Kit's upright original; diagram files keep that original. */
internal data class BatchImageGeometry(val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int) {
    fun toOriginal(box: Rect) = Rect(
        floor(box.left.toDouble() * sourceWidth / width).toInt().coerceIn(0, sourceWidth),
        floor(box.top.toDouble() * sourceHeight / height).toInt().coerceIn(0, sourceHeight),
        ceil(box.right.toDouble() * sourceWidth / width).toInt().coerceIn(0, sourceWidth),
        ceil(box.bottom.toDouble() * sourceHeight / height).toInt().coerceIn(0, sourceHeight))
}

/** Close only once the ML Kit Task completes, including when its coroutine was cancelled. */
internal class BatchImageInput(val image: InputImage, val geometry: BatchImageGeometry,
                              private val owned: List<Bitmap>) : Closeable {
    fun <T> releaseAfter(task: com.google.android.gms.tasks.Task<T>) {
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { close() }
    }
    @Synchronized override fun close() { owned.distinct().forEach { if (!it.isRecycled) it.recycle() } }
}

internal class BatchRecognitionFeatures(
    private val enhance: Boolean = BuildConfig.ENABLE_OPENCV_ENHANCEMENT,
    private val corrector: OfflineTextCorrector? = null,
    private val enhancer: WhiteboardEnhancer = OpenCvWhiteboardEnhancer()
) {
    companion object {
        fun configured(context: Context) = BatchRecognitionFeatures(corrector =
            configuredTextCorrector(context, BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION))
    }

    /** File-input factory has already applied EXIF and returns bitmap format, rotation 0. */
    fun prepare(original: InputImage, ownsOriginal: Boolean = false): BatchImageInput {
        PerformanceDiagnostics.metric(PerfMetric.OPENCV_REQUESTED, if (enhance) 1 else 0)
        PerformanceDiagnostics.metric(PerfMetric.OPENCV_APPLIED, 0)
        PerformanceDiagnostics.metric(PerfMetric.SOURCE_WIDTH, original.width.toLong())
        PerformanceDiagnostics.metric(PerfMetric.SOURCE_HEIGHT, original.height.toLong())
        PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_WIDTH, original.width.toLong())
        PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_HEIGHT, original.height.toLong())
        val bitmap = original.bitmapInternal
        val geometry = BatchImageGeometry(original.width, original.height, original.width, original.height)
        if (!enhance || bitmap == null || original.rotationDegrees != 0)
            return BatchImageInput(original, geometry, if (ownsOriginal) listOfNotNull(bitmap) else emptyList())
        val prepared = try { WhiteboardImagePreparation(true, enhancer).prepare(bitmap) }
            catch (error: Throwable) {
                if (ownsOriginal && !bitmap.isRecycled) bitmap.recycle()
                throw error
            }
        // A native enhancement fallback must preserve the original ML Kit input/scale.
        if (!prepared.applied) {
            PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_WIDTH, original.width.toLong())
            PerformanceDiagnostics.metric(PerfMetric.RECOGNITION_HEIGHT, original.height.toLong())
            if (prepared.original !== bitmap) prepared.original.recycle()
            return BatchImageInput(original, geometry, if (ownsOriginal) listOf(bitmap) else emptyList())
        }
        return BatchImageInput(InputImage.fromBitmap(prepared.recognition, 0),
            BatchImageGeometry(bitmap.width, bitmap.height, prepared.recognition.width, prepared.recognition.height),
            (if (ownsOriginal) listOf(bitmap) else emptyList()) +
                listOf(prepared.original, prepared.recognition).filter { it !== bitmap })
    }

    fun correct(rawText: String): CorrectionResult {
        PerformanceDiagnostics.metric(PerfMetric.CORRECTION_ENABLED, if (corrector != null) 1 else 0)
        if (corrector == null) return CorrectionResult(rawText, rawText)
        return PerformanceDiagnostics.measure(PerfStage.OFFLINE_CORRECTION) {
            try {
                corrector.correct(rawText).also { require(it.originalText == rawText) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w(RECOGNITION_LOG_TAG, "Batch correction failed; keeping raw OCR", error)
                CorrectionResult(rawText, rawText, skippedReason = "correction_error")
            }
        }
    }
}
