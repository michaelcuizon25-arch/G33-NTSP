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
            "ocr/crnn_note2snap.tflite"

        private const val CHARSET_FILE =
            "ocr/charset_crnn.txt"

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

    /**
     * Represents one physical handwritten line
     * extracted from a larger CCL text region.
     */
    private data class PhysicalLine(
        val bitmap: Bitmap,
        val boundingBox: Rect
    )

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
                splitIntoPhysicalLines(region)

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
     * Split a CCL text region into actual
     * physical handwritten lines.
     *
     * Uses a horizontal ink projection:
     *
     * 1. Convert pixels to grayscale.
     * 2. Estimate foreground threshold.
     * 3. Count dark pixels on each row.
     * 4. Detect continuous row groups.
     * 5. Merge very small vertical gaps.
     * 6. Crop each resulting text line.
     */
    private fun splitIntoPhysicalLines(
        region: Region
    ): List<PhysicalLine> {

        val bitmap =
            region.croppedBitmap

        val width =
            bitmap.width

        val height =
            bitmap.height

        if (
            width <= 2 ||
            height <= 2
        ) {

            return listOf(
                PhysicalLine(
                    bitmap =
                        bitmap,

                    boundingBox =
                        Rect(
                            region.boundingBox
                        )
                )
            )
        }

        /*
         * Calculate a grayscale threshold
         * using Otsu's method.
         *
         * Whiteboard:
         * background = bright
         * writing = dark
         */
        val threshold =
            calculateOtsuThreshold(
                bitmap
            )

        /*
         * Count foreground / ink pixels
         * for every horizontal row.
         */
        val rowInkCounts =
            IntArray(height)

        for (y in 0 until height) {

            var inkCount = 0

            for (x in 0 until width) {

                val gray =
                    grayscale(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

                if (gray < threshold) {
                    inkCount++
                }
            }

            rowInkCounts[y] =
                inkCount
        }

        /*
         * Require a tiny amount of ink
         * before considering the row active.
         *
         * This suppresses isolated noise pixels.
         */
        val minimumInkPerRow =
            max(
                2,
                (width * 0.004f).toInt()
            )

        val activeRows =
            BooleanArray(height)

        for (y in 0 until height) {

            activeRows[y] =
                rowInkCounts[y] >=
                        minimumInkPerRow
        }

        /*
         * Convert active rows into vertical runs.
         */
        val rawRuns =
            mutableListOf<IntRange>()

        var runStart = -1

        for (y in 0 until height) {

            if (activeRows[y]) {

                if (runStart == -1) {
                    runStart = y
                }

            } else {

                if (runStart != -1) {

                    rawRuns.add(
                        runStart..(y - 1)
                    )

                    runStart = -1
                }
            }
        }

        if (runStart != -1) {

            rawRuns.add(
                runStart..(height - 1)
            )
        }

        /*
         * No clear line detected.
         *
         * Safest fallback:
         * use the original region as one line.
         */
        if (rawRuns.isEmpty()) {

            return listOf(
                PhysicalLine(
                    bitmap =
                        bitmap,

                    boundingBox =
                        Rect(
                            region.boundingBox
                        )
                )
            )
        }

        /*
         * Merge tiny gaps caused by letters such as:
         *
         * i
         * j
         * :
         *
         * or broken handwriting strokes.
         */
        val gapTolerance =
            max(
                2,
                (height * 0.012f).toInt()
            )

        val mergedRuns =
            mutableListOf<IntRange>()

        var currentStart =
            rawRuns.first().first

        var currentEnd =
            rawRuns.first().last

        for (
        index in 1 until rawRuns.size
        ) {

            val next =
                rawRuns[index]

            val gap =
                next.first -
                        currentEnd -
                        1

            if (gap <= gapTolerance) {

                currentEnd =
                    next.last

            } else {

                mergedRuns.add(
                    currentStart..
                            currentEnd
                )

                currentStart =
                    next.first

                currentEnd =
                    next.last
            }
        }

        mergedRuns.add(
            currentStart..
                    currentEnd
        )

        /*
         * Remove extremely tiny vertical runs.
         *
         * These are usually dots/noise that were
         * not attached to a real text line.
         */
        val minimumLineHeight =
            max(
                3,
                (height * 0.025f).toInt()
            )

        val candidateRuns =
            mergedRuns.filter {

                val runHeight =
                    it.last -
                            it.first +
                            1

                runHeight >=
                        minimumLineHeight
            }

        /*
         * If filtering removed everything,
         * don't lose OCR entirely.
         */
        if (candidateRuns.isEmpty()) {

            return listOf(
                PhysicalLine(
                    bitmap =
                        bitmap,

                    boundingBox =
                        Rect(
                            region.boundingBox
                        )
                )
            )
        }

        /*
         * Create individual line crops.
         */
        val lines =
            mutableListOf<PhysicalLine>()

        for (run in candidateRuns) {

            /*
             * Small vertical padding protects
             * ascenders/descenders from clipping.
             */
            val runHeight =
                run.last -
                        run.first +
                        1

            val verticalPadding =
                max(
                    2,
                    (runHeight * 0.20f)
                        .toInt()
                )

            val top =
                max(
                    0,
                    run.first -
                            verticalPadding
                )

            val bottom =
                min(
                    height - 1,
                    run.last +
                            verticalPadding
                )

            /*
             * Find actual left/right ink bounds
             * inside this line.
             */
            var minX =
                width

            var maxX =
                -1

            for (y in top..bottom) {

                for (x in 0 until width) {

                    val gray =
                        grayscale(
                            bitmap.getPixel(
                                x,
                                y
                            )
                        )

                    if (gray < threshold) {

                        if (x < minX) {
                            minX = x
                        }

                        if (x > maxX) {
                            maxX = x
                        }
                    }
                }
            }

            /*
             * If something unusual happened,
             * use the complete horizontal width.
             */
            if (
                minX > maxX ||
                maxX < 0
            ) {

                minX = 0
                maxX = width - 1
            }

            val horizontalPadding =
                max(
                    4,
                    (
                            (maxX - minX + 1) *
                                    0.03f
                            ).toInt()
                )

            val left =
                max(
                    0,
                    minX -
                            horizontalPadding
                )

            val right =
                min(
                    width - 1,
                    maxX +
                            horizontalPadding
                )

            val cropWidth =
                right -
                        left +
                        1

            val cropHeight =
                bottom -
                        top +
                        1

            if (
                cropWidth <= 2 ||
                cropHeight <= 2
            ) {
                continue
            }

            val lineBitmap =
                Bitmap.createBitmap(
                    bitmap,
                    left,
                    top,
                    cropWidth,
                    cropHeight
                )

            /*
             * Convert local crop coordinates
             * back into global whiteboard coordinates.
             */
            val globalBox =
                Rect(
                    region.boundingBox.left +
                            left,

                    region.boundingBox.top +
                            top,

                    region.boundingBox.left +
                            right +
                            1,

                    region.boundingBox.top +
                            bottom +
                            1
                )

            lines.add(
                PhysicalLine(
                    bitmap =
                        lineBitmap,

                    boundingBox =
                        globalBox
                )
            )
        }

        /*
         * Again: never accidentally throw away
         * a valid CCL text region.
         */
        if (lines.isEmpty()) {

            return listOf(
                PhysicalLine(
                    bitmap =
                        bitmap,

                    boundingBox =
                        Rect(
                            region.boundingBox
                        )
                )
            )
        }

        /*
         * Physical reading order inside
         * this region = top to bottom.
         */
        return lines.sortedBy {
            it.boundingBox.top
        }
    }

    /**
     * Otsu grayscale threshold.
     *
     * Helps distinguish dark handwriting
     * from bright whiteboard background.
     */
    private fun calculateOtsuThreshold(
        bitmap: Bitmap
    ): Int {

        val histogram =
            IntArray(256)

        val width =
            bitmap.width

        val height =
            bitmap.height

        var totalPixels = 0

        /*
         * Sampling every pixel is okay for
         * normal CCL text crops.
         */
        for (y in 0 until height) {

            for (x in 0 until width) {

                val gray =
                    grayscale(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

                histogram[gray]++

                totalPixels++
            }
        }

        var totalWeighted =
            0.0

        for (i in 0..255) {

            totalWeighted +=
                i.toDouble() *
                        histogram[i]
        }

        var backgroundWeight = 0
        var backgroundSum = 0.0

        var maximumVariance = -1.0

        var bestThreshold = 128

        for (threshold in 0..255) {

            backgroundWeight +=
                histogram[threshold]

            if (backgroundWeight == 0) {
                continue
            }

            val foregroundWeight =
                totalPixels -
                        backgroundWeight

            if (foregroundWeight == 0) {
                break
            }

            backgroundSum +=
                threshold.toDouble() *
                        histogram[threshold]

            val backgroundMean =
                backgroundSum /
                        backgroundWeight

            val foregroundMean =
                (
                        totalWeighted -
                                backgroundSum
                        ) /
                        foregroundWeight

            val difference =
                backgroundMean -
                        foregroundMean

            val variance =
                backgroundWeight
                    .toDouble() *
                        foregroundWeight
                            .toDouble() *
                        difference *
                        difference

            if (variance > maximumVariance) {

                maximumVariance =
                    variance

                bestThreshold =
                    threshold
            }
        }

        /*
         * Avoid overly dark threshold
         * on very clean white backgrounds.
         */
        return bestThreshold
            .coerceIn(
                60,
                230
            )
    }

    /**
     * RGB -> grayscale.
     *
     * Matches the conceptual grayscale
     * conversion used before CRNN input.
     */
    private fun grayscale(
        pixel: Int
    ): Int {

        val r =
            Color.red(pixel)

        val g =
            Color.green(pixel)

        val b =
            Color.blue(pixel)

        return (
                0.299f * r +
                        0.587f * g +
                        0.114f * b
                )
            .toInt()
            .coerceIn(
                0,
                255
            )
    }

    /**
     * Preprocess ONE physical text line.
     *
     * Current implementation:
     *
     * grayscale
     * -> aspect-ratio preserving resize
     * -> 512 × 32 white canvas
     * -> pad right/bottom
     * -> Float32 normalization
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
                    gray
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

        return context.assets
            .open(
                CHARSET_FILE
            )
            .bufferedReader(
                Charsets.UTF_8
            )
            .useLines { lines ->

                lines.toList()
            }
    }

    override fun close() {

        interpreter.close()
    }
}