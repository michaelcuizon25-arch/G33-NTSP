package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import com.example.note2snap.ccl.Region
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * CRNN handwriting recognizer for Note2Snap.
 *
 * Model input:
 * [1, 512, 32] Float32 (confirmed in the supplied Epoch 3 asset)
 *
 * Model output:
 * [1, 128, 80] Float32
 *
 * Character classes:
 * 0..78
 *
 * CTC blank:
 * 79
 *
 * IMPORTANT:
 * The CRNN was trained using LINE-LEVEL images.
 *
 * Therefore:
 *
 * CCL text region
 *      ↓
 * physical line segmentation
 *      ↓
 * one line crop
 *      ↓
 * CRNN
 */
class CrnnLineRecognizer internal constructor(
    private val context: Context,
    preprocessingMode: CrnnPreprocessingMode,
    private val runtime: CrnnInferenceRuntime,
    private val charset: List<String>
) : LineRecognizer {
    @JvmOverloads
    constructor(context: Context, preprocessingMode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY) :
            this(context, preprocessingMode, TensorFlowLiteCrnnRuntime(context), loadCrnnCharset(context))

    companion object {
        private const val CTC_BLANK_INDEX = 79
    }

    internal val diagnostics = CrnnDiagnostics(context)
    private val inputPreprocessor = CrnnPreprocessing(preprocessingMode)

    init {

        require(charset.size == 79) {
            "CRNN charset must contain 79 characters, " +
                    "but found ${charset.size}"
        }
        diagnostics.recordModel(context, CrnnModelAssets.MODEL, CrnnModelAssets.CHARSET, charset,
            runtime.inputShape, runtime.outputShape, runtime.inputType, runtime.outputType,
            inputSignature = runtime.inputShapeSignature, outputSignature = runtime.outputShapeSignature,
            cachedOutputShape = runtime.cachedOutputShape)
    }

    private val lineSegmenter = PhysicalLineSegmenter()

    /**
     * Recognize every CCL TEXT region.
     *
     * A single CCL region may contain multiple
     * physical lines, so we split first.
     */
    override suspend fun recognize(
        regions: List<Region>
    ): List<RecognizedLine> {

        val recognizedLines = mutableListOf<RecognizedLine>()
        var physicalCount = 0
        var successfulCount = 0
        var blankCount = 0
        var failedCount = 0
        var firstFailure: Exception? = null
        Log.i(RECOGNITION_LOG_TAG, "CRNN received textRegions=${regions.size}")
        for (region in regions) {
            val physicalLines = lineSegmenter.splitIntoPhysicalLines(region)
            physicalCount += physicalLines.size
            Log.i(RECOGNITION_LOG_TAG, "Segmentation region=${region.id} box=${region.boundingBox} physicalLines=${physicalLines.size}")
            for (line in physicalLines) {
                var stage = "preprocessing"
                var diagnosticId: Int? = null
                try {
                    val prepared = inputPreprocessor.prepare(line.bitmap)
                    val input = prepared.input
                    diagnosticId = diagnostics.beginLine(region, line, input, prepared)
                    Log.d(RECOGNITION_LOG_TAG, "CRNN line=$diagnosticId region=${region.id} box=${line.boundingBox} " +
                            "crop=${line.bitmap.width}x${line.bitmap.height} requested=${prepared.requestedMode} " +
                            "actual=${prepared.actualMode} referenceWidth=${prepared.referenceWidth} fallback=${prepared.fallbackReason}")
                    stage = "inference"
                    val output = runtime.run(input)
                    if (successfulCount == 0) {
                        diagnostics.recordModel(context, CrnnModelAssets.MODEL, CrnnModelAssets.CHARSET, charset,
                            runtime.inputShape, runtime.outputShape, runtime.inputType, runtime.outputType,
                            outputShapeStage = "After successful inference; resolved shape verified from native byte count",
                            inputSignature = runtime.inputShapeSignature, outputSignature = runtime.outputShapeSignature,
                            cachedOutputShape = runtime.cachedOutputShape)
                    }
                    stage = "decoding"
                    val rawText = decodeCtc(output)
                    val confidence = calculateConfidence(output)
                    val text = rawText.trim().replace(Regex("\\s+"), " ")
                    diagnostics.completeLine(diagnosticId, rawText, text, confidence, output)
                    successfulCount++
                    Log.i(RECOGNITION_LOG_TAG, "CRNN line=$diagnosticId predicted=${JSONObject.quote(text)} blank=${text.isBlank()}")
                    if (text.isBlank()) blankCount++
                    else recognizedLines.add(RecognizedLine(region.id, line.boundingBox, text, confidence))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failedCount++
                    if (firstFailure == null) firstFailure = error
                    diagnosticId?.let { diagnostics.failLine(it, error, stage) }
                    Log.e(RECOGNITION_LOG_TAG, "CRNN failure stage=$stage region=${region.id} box=${line.boundingBox} " +
                            "crop=${line.bitmap.width}x${line.bitmap.height}", error)
                }
            }
        }
        val status = when {
            failedCount > 0 && recognizedLines.isEmpty() -> "recognition_failed"
            failedCount > 0 -> "partial_success"
            regions.isEmpty() -> "no_text_regions"
            physicalCount == 0 -> "no_physical_lines"
            recognizedLines.isEmpty() -> "all_predictions_blank"
            else -> "recognized"
        }
        diagnostics.recordRecognitionSummary(JSONObject().put("status", status)
            .put("textRegions", regions.size).put("physicalLines", physicalCount)
            .put("successfulLines", successfulCount).put("blankPredictions", blankCount)
            .put("failedLines", failedCount).put("recognizedLines", recognizedLines.size))
        Log.i(RECOGNITION_LOG_TAG, "CRNN summary status=$status textRegions=${regions.size} physicalLines=$physicalCount " +
                "successful=$successfulCount blank=$blankCount failed=$failedCount recognized=${recognizedLines.size}")
        if (firstFailure != null && recognizedLines.isEmpty())
            throw CrnnRecognitionFailure(failedCount, physicalCount, firstFailure)
        return recognizedLines
    }

    /**
     * CTC prefix beam search over model logits.
     *
     * Unlike greedy decoding, this keeps several likely character sequences
     * alive across time steps. It can improve decoding when the best character
     * at an individual time step does not lead to the best overall sequence.
     *
     * This does not replace model training and does not guarantee better text
     * for every image. The model output is treated as logits, then normalized
     * with log-softmax before decoding.
     */
    private fun decodeCtc(
        output: Array<Array<FloatArray>>
    ): String {
        require(output.isNotEmpty() && output[0].isNotEmpty()) {
            "CRNN output is empty"
        }

        val sequence = output[0]
        require(sequence.all { it.size == CTC_BLANK_INDEX + 1 }) {
            "Expected ${CTC_BLANK_INDEX + 1} output classes per timestep"
        }

        val beamWidth = 8
        val negativeInfinity = Double.NEGATIVE_INFINITY

        data class BeamScore(
            var blank: Double = Double.NEGATIVE_INFINITY,
            var nonBlank: Double = Double.NEGATIVE_INFINITY
        ) {
            fun total(): Double = logAdd(blank, nonBlank)
        }

        var beams = mutableMapOf<List<Int>, BeamScore>()
        beams[emptyList()] = BeamScore(blank = 0.0)

        for (logits in sequence) {
            val logProbabilities = logSoftmax(logits)
            val next = mutableMapOf<List<Int>, BeamScore>()

            fun state(prefix: List<Int>): BeamScore =
                next.getOrPut(prefix) { BeamScore() }

            for ((prefix, score) in beams) {
                val totalScore = score.total()

                // A blank keeps the prefix unchanged.
                val blankState = state(prefix)
                blankState.blank = logAdd(
                    blankState.blank,
                    totalScore + logProbabilities[CTC_BLANK_INDEX]
                )

                for (characterIndex in 0 until CTC_BLANK_INDEX) {
                    val charScore = logProbabilities[characterIndex]
                    if (charScore == negativeInfinity) continue

                    if (prefix.isNotEmpty() && prefix.last() == characterIndex) {
                        // Same character without an intervening blank collapses.
                        val samePrefixState = state(prefix)
                        samePrefixState.nonBlank = logAdd(
                            samePrefixState.nonBlank,
                            score.nonBlank + charScore
                        )

                        // A repeated character after a blank creates a new token.
                        val extendedPrefix = prefix + characterIndex
                        val extendedState = state(extendedPrefix)
                        extendedState.nonBlank = logAdd(
                            extendedState.nonBlank,
                            score.blank + charScore
                        )
                    } else {
                        val extendedPrefix = prefix + characterIndex
                        val extendedState = state(extendedPrefix)
                        extendedState.nonBlank = logAdd(
                            extendedState.nonBlank,
                            totalScore + charScore
                        )
                    }
                }
            }

            beams = next.entries
                .sortedByDescending { it.value.total() }
                .take(beamWidth)
                .associateTo(mutableMapOf()) { it.key to it.value }
        }

        val bestPrefix = beams.maxByOrNull { it.value.total() }?.key.orEmpty()
        return buildString {
            bestPrefix.forEach { index ->
                if (index in charset.indices) append(charset[index])
            }
        }
    }

    /**
     * Returns stable log-softmax values for a single timestep of model logits.
     */
    private fun logSoftmax(logits: FloatArray): DoubleArray {
        require(logits.isNotEmpty()) { "CRNN timestep has no classes" }
        val maxLogit = logits.maxOrNull()?.toDouble()
            ?: return DoubleArray(0)
        var sum = 0.0
        for (value in logits) {
            sum += kotlin.math.exp(value.toDouble() - maxLogit)
        }
        val logNormalizer = maxLogit + kotlin.math.ln(sum)
        return DoubleArray(logits.size) { index ->
            logits[index].toDouble() - logNormalizer
        }
    }

    private fun logAdd(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        val maxValue = maxOf(a, b)
        return maxValue + kotlin.math.ln(
            kotlin.math.exp(a - maxValue) + kotlin.math.exp(b - maxValue)
        )
    }

    /**
     * Approximate line confidence based on softmax probabilities at emitted
     * greedy character timesteps. This is a diagnostic score, not verified OCR
     * accuracy and not a calibrated probability for the beam-search result.
     */
    private fun calculateConfidence(
        output: Array<Array<FloatArray>>
    ): Float {
        if (output.isEmpty() || output[0].isEmpty()) return 0f

        var total = 0.0
        var count = 0
        var previousIndex = -1

        for (logits in output[0]) {
            if (logits.isEmpty()) continue

            var bestIndex = 0
            for (i in 1 until logits.size) {
                if (logits[i] > logits[bestIndex]) bestIndex = i
            }

            if (bestIndex == CTC_BLANK_INDEX) {
                previousIndex = CTC_BLANK_INDEX
                continue
            }
            if (bestIndex == previousIndex) continue

            val maxLogit = logits.maxOrNull()?.toDouble() ?: 0.0
            var denominator = 0.0
            for (value in logits) {
                denominator += kotlin.math.exp(value.toDouble() - maxLogit)
            }
            val selectedProbability =
                kotlin.math.exp(logits[bestIndex].toDouble() - maxLogit) / denominator

            total += selectedProbability
            count++
            previousIndex = bestIndex
        }

        return if (count == 0) 0f else (total / count).toFloat()
    }

    override fun close() {

        runtime.close()
    }
}
