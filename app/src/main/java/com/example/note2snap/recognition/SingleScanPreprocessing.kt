package com.example.note2snap.recognition

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log

internal enum class SingleScanSource { CAMERA, GALLERY, TUTORIAL }
internal data class SingleScanRequest(val source: SingleScanSource, val mode: CrnnPreprocessingMode)

/** Isolated debug preference; release scans ignore even an existing debug value. */
internal class SingleScanPreprocessingSettings internal constructor(
    private val preferences: SharedPreferences,
    val isDebugEnabled: Boolean
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("single_crnn_debug", Context.MODE_PRIVATE),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    )

    val selectedMode: CrnnPreprocessingMode
        get() = if (!isDebugEnabled) CrnnPreprocessingMode.LEGACY else
            CrnnPreprocessingMode.values().firstOrNull { it.name == preferences.getString(KEY, null) }
                ?: CrnnPreprocessingMode.LEGACY

    fun select(mode: CrnnPreprocessingMode): Boolean {
        if (!isDebugEnabled) return false
        preferences.edit().putString(KEY, mode.name).apply()
        return true
    }

    fun snapshot(source: SingleScanSource) = SingleScanRequest(source, selectedMode)

    private companion object { const val KEY = "preprocessing_mode" }
}

/** Shared by Single Camera/Gallery; never used by either ML Kit Batch path. */
internal class SingleScanPipelineFactory(
    private val recognizerFactory: (Context, CrnnPreprocessingMode) -> LineRecognizer =
        { context, mode -> CrnnLineRecognizer(context, mode) }
) {
    fun create(context: Context, request: SingleScanRequest): WhiteboardRecognitionPipeline {
        Log.i(RECOGNITION_LOG_TAG, "Single scan source=${request.source} preprocessing=${request.mode}")
        return WhiteboardRecognitionPipeline(context, request.mode, recognizerFactory = recognizerFactory)
    }
}
