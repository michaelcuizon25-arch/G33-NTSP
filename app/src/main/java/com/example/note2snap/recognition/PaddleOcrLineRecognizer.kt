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

internal data class PaddleLineResult(val prediction: PaddlePrediction, val preprocessingNs: Long, val inferenceNs: Long, val decodingNs: Long)

/** Optional line recognizer. Production uses recognizeLine only as a shadow of the exact CRNN crop. */
internal class PaddleOcrLineRecognizer(
    private val runtime: PaddleRecognitionRuntime,
    private val preprocessor: PaddleInputPreprocessor,
    private val decoder: PaddleCtcDecoder
) : LineRecognizer {
    constructor(context: Context) : this(OnnxPaddleRecognitionRuntime(context), PaddleInputPreprocessor(),
        PaddleCtcDecoder(PaddleModelAssets.characters(context)))

    suspend fun recognizeLine(bitmap: Bitmap): PaddleLineResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        val start = System.nanoTime()
        val input = PerformanceDiagnostics.measure(PerfStage.PADDLE_PREPROCESSING) { preprocessor.prepare(bitmap) }
        val prepared = System.nanoTime()
        val output = runtime.run(input)
        val inferred = System.nanoTime()
        coroutineContext.ensureActive()
        val prediction = PerformanceDiagnostics.measure(PerfStage.PADDLE_CTC_DECODE) { decoder.decode(output) }
        PaddleLineResult(prediction, prepared - start, inferred - prepared, System.nanoTime() - inferred)
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
