package com.example.note2snap.recognition

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Explicit experiment selection; existing callers continue to use LEGACY. */
enum class CrnnPreprocessingMode { LEGACY, SIMPLE_HTR_REFERENCE }

internal data class PreparedCrnnInput(
    val input: ByteBuffer,
    val requestedMode: CrnnPreprocessingMode,
    val actualMode: CrnnPreprocessingMode,
    val referenceWidth: Int? = null,
    val fallbackReason: String? = null
)

internal class CrnnPreprocessing(private val mode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY) {
    private val legacy = CrnnInputPreprocessor()
    private val reference = SimpleHtrReferencePreprocessor()

    fun prepare(bitmap: Bitmap): PreparedCrnnInput {
        if (mode == CrnnPreprocessingMode.LEGACY)
            return PreparedCrnnInput(legacy.preprocess(bitmap), mode, mode)
        val result = reference.preprocess(bitmap)
        if (result.input != null)
            return PreparedCrnnInput(result.input, mode, mode, result.dynamicWidth)
        return PreparedCrnnInput(legacy.preprocess(bitmap), mode, CrnnPreprocessingMode.LEGACY,
            result.dynamicWidth, "reference_width_exceeds_512")
    }
}
