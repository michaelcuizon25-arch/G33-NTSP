package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import com.example.note2snap.BuildConfig

internal enum class SingleScanRecognitionMode { CRNN_ONLY, PADDLE_PRIMARY_WITH_CRNN_FALLBACK }

internal fun configuredSingleScanRecognizer(context: Context, mode: CrnnPreprocessingMode): LineRecognizer {
    val selection = SingleScanRecognitionMode.valueOf(BuildConfig.SINGLE_SCAN_RECOGNITION_MODE)
    Log.i(RECOGNITION_LOG_TAG, "Single scan recognitionMode=$selection")
    return when (selection) {
        SingleScanRecognitionMode.CRNN_ONLY -> CrnnLineRecognizer(context, mode)
        SingleScanRecognitionMode.PADDLE_PRIMARY_WITH_CRNN_FALLBACK -> PaddlePrimaryLineRecognizer(context, mode)
    }
}
