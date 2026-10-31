package com.example.note2snap.recognition
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage

import android.content.Context
import android.util.Log
import com.example.note2snap.correction.BundledTextCorrector
import com.example.note2snap.correction.CorrectionResult
import com.example.note2snap.correction.OfflineTextCorrector
import kotlinx.coroutines.CancellationException

/** Index refers to the original recognition list, not the later reading-order list. */
data class LineTextCorrection(val lineIndex: Int, val sourceRegionId: Int, val result: CorrectionResult)

internal fun configuredTextCorrector(context: Context, enabled: Boolean): OfflineTextCorrector? {
    if (!enabled) return null
    return try {
        PerformanceDiagnostics.measure(PerfStage.CORRECTOR_INITIALIZATION) { BundledTextCorrector.load(context.applicationContext) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(RECOGNITION_LOG_TAG, "Offline correction unavailable; keeping raw OCR", error)
        null
    }
}

/** Recognition failures are handled by recognizers; correction can only alter text. */
internal fun correctRecognizedLines(lines: List<RecognizedLine>, corrector: OfflineTextCorrector): List<LineTextCorrection> =
    lines.mapIndexed { index, line ->
        val result = try {
            corrector.correct(line.text).also { require(it.originalText == line.text) { "Correction original-text mismatch" } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(RECOGNITION_LOG_TAG, "Offline correction failed for line $index; keeping raw OCR", error)
            CorrectionResult(line.text, line.text, skippedReason = "correction_error")
        }
        LineTextCorrection(index, line.sourceRegionId, result)
    }
