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
     * Greedy CTC decoding.
     *
     * Same principle as:
     *
     * tf.keras.backend.ctc_decode(
     *     greedy = true
     * )
     */
    private fun decodeCtc(
        output:
        Array<Array<FloatArray>>
    ): String {

        val sequence =
            output[0]

        val result =
            StringBuilder()

        var previousIndex = -1

        for (
        t in sequence.indices
        ) {

            val probabilities =
                sequence[t]

            var bestIndex = 0

            var bestProbability =
                probabilities[0]

            for (
            i in 1 until probabilities.size
            ) {

                if (
                    probabilities[i] >
                    bestProbability
                ) {

                    bestProbability =
                        probabilities[i]

                    bestIndex = i
                }
            }

            /*
             * Blank resets repetition.
             *
             * Example:
             *
             * l l blank l
             *
             * becomes:
             *
             * ll
             */
            if (
                bestIndex ==
                CTC_BLANK_INDEX
            ) {

                previousIndex =
                    CTC_BLANK_INDEX

                continue
            }

            /*
             * Collapse consecutive duplicate
             * non-blank predictions.
             */
            if (
                bestIndex ==
                previousIndex
            ) {
                continue
            }

            if (
                bestIndex in
                charset.indices
            ) {

                result.append(
                    charset[
                        bestIndex
                    ]
                )
            }

            previousIndex =
                bestIndex
        }

        return result.toString()
    }

    /**
     * Existing average selected raw output for decoded non-blank characters.
     * The Epoch 3 asset exports logits: this is not a calibrated probability.
     * Preserved unchanged during this diagnostic investigation.
     */
    private fun calculateConfidence(
        output:
        Array<Array<FloatArray>>
    ): Float {

        val sequence =
            output[0]

        var total = 0f
        var count = 0

        var previousIndex = -1

        for (
        probabilities in sequence
        ) {

            var bestIndex = 0

            var bestProbability =
                probabilities[0]

            for (
            i in 1 until probabilities.size
            ) {

                if (
                    probabilities[i] >
                    bestProbability
                ) {

                    bestProbability =
                        probabilities[i]

                    bestIndex = i
                }
            }

            if (
                bestIndex ==
                CTC_BLANK_INDEX
            ) {

                previousIndex =
                    CTC_BLANK_INDEX

                continue
            }

            if (
                bestIndex ==
                previousIndex
            ) {
                continue
            }

            total +=
                bestProbability

            count++

            previousIndex =
                bestIndex
        }

        return if (
            count == 0
        ) {

            0f

        } else {

            total /
                    count
        }
    }

    override fun close() {

        runtime.close()
    }
}
