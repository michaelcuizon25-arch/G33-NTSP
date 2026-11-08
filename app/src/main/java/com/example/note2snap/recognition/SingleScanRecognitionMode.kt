package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import com.example.note2snap.BuildConfig

internal enum class SingleScanRecognitionMode { CRNN_ONLY, PADDLE_PRIMARY_WITH_CRNN_FALLBACK }

internal fun configuredSingleScanRecognizer(context: Context, mode: CrnnPreprocessingMode): LineRecognizer {
    val selection = SingleScanRecognitionMode.valueOf(BuildConfig.SINGLE_SCAN_RECOGNITION_MODE)
    Log.i(RECOGNITION_LOG_TAG, "Single scan recognitionMode=$selection " +
        "correctionRequested=${BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION} " +
        "cropRefinement=${BuildConfig.ENABLE_PADDLE_CROP_REFINEMENT} " +
        "primaryExports=${BuildConfig.ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS} borderFilter=frame-track-v1.1+side-border-587e0e6")
    return when (selection) {
        SingleScanRecognitionMode.CRNN_ONLY -> CrnnLineRecognizer(context, mode)
        SingleScanRecognitionMode.PADDLE_PRIMARY_WITH_CRNN_FALLBACK -> PaddlePrimaryLineRecognizer(context, mode)
    }
}
