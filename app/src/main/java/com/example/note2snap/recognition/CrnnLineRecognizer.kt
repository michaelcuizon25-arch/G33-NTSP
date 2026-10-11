package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import com.example.note2snap.ccl.Region
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
class CrnnLineRecognizer @JvmOverloads constructor(
    private val context: Context,
    preprocessingMode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY
) : LineRecognizer {

    companion object {

        private const val MODEL_FILE =
            "ocr/note2snap_crnn_v2_epoch03_flex.tflite"

        private const val CHARSET_FILE =
            "ocr/note2snap_crnn_v2_epoch03_charlist.txt"

        private const val INPUT_WIDTH = 512
        private const val INPUT_HEIGHT = 32

        private const val TIME_STEPS = 128
        private const val NUM_CLASSES = 80

        private const val CTC_BLANK_INDEX = 79
    }

    private val interpreter: Interpreter =
        Interpreter(
            loadModel(context)
        )

    private val charset: List<String> =
        loadCharset(context)

    internal val diagnostics = CrnnDiagnostics(context)
    private val inputPreprocessor = CrnnPreprocessing(preprocessingMode)

    init {

        require(charset.size == 79) {
            "CRNN charset must contain 79 characters, " +
                    "but found ${charset.size}"
        }
        diagnostics.recordModel(context, MODEL_FILE, CHARSET_FILE, charset,
            interpreter.getInputTensor(0).shape(), interpreter.getOutputTensor(0).shape(),
            interpreter.getInputTensor(0).dataType().toString(),
            interpreter.getOutputTensor(0).dataType().toString())
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

        val recognizedLines =
            mutableListOf<RecognizedLine>()

        for (region in regions) {

            val physicalLines =
                lineSegmenter.splitIntoPhysicalLines(region)

            for (line in physicalLines) {

                try {

                    val prepared = inputPreprocessor.prepare(line.bitmap)
                    val input = prepared.input
                    val diagnosticId = diagnostics.beginLine(region, line, input, prepared)
                    val output = try {
                        recognizeInput(input)
                    } catch (error: Exception) {
                        diagnostics.failLine(diagnosticId, error)
                        throw error
                    }
                    val rawText = decodeCtc(output)
                    val confidence = calculateConfidence(output)

                    val text =
                        rawText
                            .trim()
                            .replace(
                                Regex("\\s+"),
                                " "
                            )

                    diagnostics.completeLine(diagnosticId, rawText, text, confidence, output)

                    if (text.isNotBlank()) {

                        recognizedLines.add(
                            RecognizedLine(
                                sourceRegionId =
                                    region.id,

                                boundingBox =
                                    line.boundingBox,

                                text =
                                    text,

                                confidence =
                                    confidence
                            )
                        )
                    }

                } catch (e: Exception) {

                    e.printStackTrace()
                }
            }
        }

        return recognizedLines
    }

    /**
     * Run TFLite CRNN.
     */
    private fun recognizeInput(
        input: ByteBuffer
    ): Array<Array<FloatArray>> {

        /*
         * Java/Kotlin shape:
         *
         * Array<Array<FloatArray>>
         *
         * =
         *
         * [1][128][80]
         */
        val output =
            Array(1) {

                Array(TIME_STEPS) {

                    FloatArray(
                        NUM_CLASSES
                    )
                }
            }

        interpreter.run(
            input,
            output
        )

        return output
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

    /**
     * Load TFLite model.
     */
    private fun loadModel(
        context: Context
    ): ByteBuffer {

        val fileDescriptor =
            context.assets.openFd(
                MODEL_FILE
            )

        FileInputStream(
            fileDescriptor.fileDescriptor
        ).use { inputStream ->

            val fileChannel =
                inputStream.channel

            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    /**
     * Load exact CRNN vocabulary.
     */
    private fun loadCharset(
        context: Context
    ): List<String> {
        // Epoch 3 stores all 79 characters on ONE line.
        // Do not call trim(): the FIRST character is a space.
        val chars = context.assets.open(CHARSET_FILE)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .removePrefix("\uFEFF")
            .trimEnd('\r', '\n')

        return chars.map { it.toString() }
    }

    override fun close() {

        interpreter.close()
    }
}
