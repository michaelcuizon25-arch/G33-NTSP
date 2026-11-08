package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import com.example.note2snap.ccl.Region
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class PaddleLineResult(val prediction: PaddlePrediction, val preprocessingNs: Long, val inferenceNs: Long, val decodingNs: Long,
    val cropRefinement: PaddleCropRefinement? = null, val refinementNs: Long = 0)

/** Per-line Paddle adapter. Input-only refinement never changes caller geometry or fallback pixels. */
internal class PaddleOcrLineRecognizer(
    private val runtime: PaddleRecognitionRuntime,
    private val preprocessor: PaddleInputPreprocessor,
    private val decoder: PaddleCtcDecoder,
    private val refineCrops: Boolean = false
) : LineRecognizer {
    constructor(context: Context, refineCrops: Boolean = false) : this(OnnxPaddleRecognitionRuntime(context), PaddleInputPreprocessor(),
        PaddleCtcDecoder(PaddleModelAssets.characters(context)), refineCrops)

    suspend fun recognizeLine(bitmap: Bitmap): PaddleLineResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        var modelBitmap = bitmap
        var refinement: PaddleCropRefinement? = null
        var refinementNs = 0L
        if (refineCrops) {
            val started = System.nanoTime()
            PerformanceDiagnostics.measure(PerfStage.PADDLE_CROP_REFINEMENT) {
                try {
                    val decision = PaddleCropRefiner.analyze(bitmap)
                    if (decision.applied)
                        modelBitmap = Bitmap.createBitmap(bitmap, 0, decision.top, bitmap.width, decision.inputHeight)
                    refinement = decision
                } catch (_: Exception) {
                    // Failure to verify a margin cannot trigger a recognition-engine switch.
                    refinement = PaddleCropRefinement(bitmap.width, bitmap.height, reason = "analysis_failed")
                    modelBitmap = bitmap
                }
            }
            refinementNs = System.nanoTime() - started
        }
        try {
            val start = System.nanoTime()
            val input = PerformanceDiagnostics.measure(PerfStage.PADDLE_PREPROCESSING) { preprocessor.prepare(modelBitmap) }
            val prepared = System.nanoTime()
            val output = runtime.run(input)
            val inferred = System.nanoTime()
            coroutineContext.ensureActive()
            val prediction = PerformanceDiagnostics.measure(PerfStage.PADDLE_CTC_DECODE) { decoder.decode(output) }
            PaddleLineResult(prediction, prepared - start, inferred - prepared, System.nanoTime() - inferred,
                refinement, refinementNs)
        } finally {
            if (modelBitmap !== bitmap && !modelBitmap.isRecycled) modelBitmap.recycle()
        }
    }

    override suspend fun recognize(regions: List<Region>): List<RecognizedLine> = withContext(Dispatchers.Default) {
        val results = mutableListOf<RecognizedLine>()
        for (region in regions) for (line in PhysicalLineSegmenter().splitIntoPhysicalLines(region)) {
            try {
                val result = recognizeLine(line.bitmap)
                if (result.prediction.text.isNotBlank()) results.add(RecognizedLine(region.id, line.boundingBox,
                    result.prediction.text, result.prediction.meanSelectedProbability))
            } finally { if (line.bitmap !== region.croppedBitmap && !line.bitmap.isRecycled) line.bitmap.recycle() }
        }
        results
    }
    override fun close() = runtime.close()
}
