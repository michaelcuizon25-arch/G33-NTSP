package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * CRNN handwriting recognizer for Note2Snap.
 *
 * Model input:
 * [1, 512, 32, 1] Float32
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
class CrnnLineRecognizer(
    private val context: Context
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

    /*
     * TEMPORARY DEBUG:
     * Used to give every saved CRNN crop
     * a unique filename.
     *
     * Remove this after debugging.
     */
    private var debugCropIndex = 0

    init {

        require(charset.size == 79) {
            "CRNN charset must contain 79 characters, " +
                    "but found ${charset.size}"
        }
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

                    /*
                     * TEMPORARY DEBUG:
                     *
                     * Save the exact physical-line crop
                     * that will be sent into the CRNN.
                     *
                     * This lets us determine whether
                     * recognition errors come from:
                     *
                     * 1. CCL / line segmentation, or
                     * 2. the CRNN model itself.
                     */
                    val debugFile =
                        File(
                            context.cacheDir,
                            "crnn_test_crop_${debugCropIndex++}.png"
                        )

                    FileOutputStream(
                        debugFile
                    ).use { outputStream ->

                        line.bitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            outputStream
                        )
                    }

                    android.util.Log.d(
                        "CRNN_TEST",
                        "Saved crop: ${debugFile.absolutePath}"
                    )

                    val output =
                        recognizeBitmap(
                            line.bitmap
                        )

                    val text =
                        decodeCtc(output)
                            .trim()
                            .replace(
                                Regex("\\s+"),
                                " "
                            )

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
                                    calculateConfidence(
                                        output
                                    )
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
     * Preprocess ONE physical text line.
     *
     * Epoch 3 implementation:
     *
     * grayscale
     * -> aspect-ratio preserving resize
     * -> 512 × 32 white canvas
     * -> pad right/bottom
     * -> Float32 normalization (gray / 255 - 0.5)
     * -> transpose to [512, 32, 1]
     *
     * This matches the current Android implementation
     * being tested against the notebook pipeline.
     */
    private fun preprocess(
        bitmap: Bitmap
    ): ByteBuffer {

        // 1. Convert to grayscale
        val grayBitmap =
            Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(grayBitmap)

        val paint =
            Paint()

        val colorMatrix =
            ColorMatrix().apply {
                setSaturation(0f)
            }

        paint.colorFilter =
            ColorMatrixColorFilter(
                colorMatrix
            )

        canvas.drawBitmap(
            bitmap,
            0f,
            0f,
            paint
        )

        // 2. Preserve aspect ratio
        val scale =
            min(
                INPUT_WIDTH.toFloat() /
                        grayBitmap.width,

                INPUT_HEIGHT.toFloat() /
                        grayBitmap.height
            )

        val resizedWidth =
            max(
                1,
                (
                        grayBitmap.width *
                                scale
                        ).roundToInt()
            )

        val resizedHeight =
            max(
                1,
                (
                        grayBitmap.height *
                                scale
                        ).roundToInt()
            )

        val resized =
            Bitmap.createScaledBitmap(
                grayBitmap,
                resizedWidth,
                resizedHeight,
                true
            )

        // 3. Create 512 × 32 white canvas
        val letterboxed =
            Bitmap.createBitmap(
                INPUT_WIDTH,
                INPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
            )

        letterboxed.eraseColor(
            Color.WHITE
        )

        /*
         * Match the notebook:
         *
         * resized image starts at:
         * x = 0
         * y = 0
         *
         * Remaining space is padded on
         * the right and bottom.
         */
        val letterboxCanvas =
            Canvas(letterboxed)

        letterboxCanvas.drawBitmap(
            resized,
            0f,
            0f,
            null
        )

        // 4. Create Float32 TFLite input
        val inputBuffer =
            ByteBuffer.allocateDirect(
                4 *
                        INPUT_WIDTH *
                        INPUT_HEIGHT
            ).order(
                ByteOrder.nativeOrder()
            )

        /*
         * Notebook:
         *
         * original:
         * [32, 512, 1]
         *
         * transpose:
         * [512, 32, 1]
         *
         * Therefore x is the outer loop
         * and y is the inner loop.
         */
        for (
        x in 0 until INPUT_WIDTH
        ) {

            for (
            y in 0 until INPUT_HEIGHT
            ) {

                val pixel =
                    letterboxed.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)

                val gray =
                    (
                            0.299f * r +
                                    0.587f * g +
                                    0.114f * b
                            ) / 255f

                inputBuffer.putFloat(
                    gray - 0.5f
                )
            }
        }

        inputBuffer.rewind()

        return inputBuffer
    }

    /**
     * Run TFLite CRNN.
     */
    private fun recognizeBitmap(
        bitmap: Bitmap
    ): Array<Array<FloatArray>> {

        val input =
            preprocess(
                bitmap
            )

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
     * Average selected softmax probability
     * for decoded non-blank characters.
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