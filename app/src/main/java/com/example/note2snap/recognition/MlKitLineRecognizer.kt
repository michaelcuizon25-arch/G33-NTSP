package com.example.note2snap.recognition

import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Performs OCR on every CCL TEXT region.
 *
 * Important:
 * - CCL decides TEXT vs VISUAL first.
 * - ML Kit only receives TEXT regions.
 * - Multiple ML Kit fragments on the same physical
 *   handwritten row are reordered left-to-right.
 * - Nearby fragments on the same row may be joined.
 */
class MlKitLineRecognizer : LineRecognizer {

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    override suspend fun recognize(
        regions: List<Region>
    ): List<RecognizedLine> =
        withContext(Dispatchers.Default) {

            val recognizedLines =
                mutableListOf<RecognizedLine>()

            /*
             * Do NOT globally sort everything here.
             *
             * Global reading order belongs to
             * PipelineTextStructurer because the board
             * may contain multiple columns.
             */
            for (region in regions) {

                recognizedLines +=
                    recognizeRegion(
                        region
                    )
            }

            recognizedLines
        }

    private suspend fun recognizeRegion(
        region: Region
    ): List<RecognizedLine> =
        suspendCancellableCoroutine { continuation ->

            val image =
                InputImage.fromBitmap(
                    region.croppedBitmap,
                    0
                )

            recognizer
                .process(image)
                .addOnSuccessListener { result ->

                    if (!continuation.isActive) {
                        return@addOnSuccessListener
                    }

                    val rawLines =
                        mutableListOf<RecognizedLine>()

                    for (block in result.textBlocks) {

                        for (line in block.lines) {

                            val text =
                                cleanText(
                                    line.text
                                )

                            if (text.isBlank()) {
                                continue
                            }

                            val localBox =
                                line.boundingBox

                            val globalBox =
                                if (localBox != null) {

                                    Rect(
                                        region.boundingBox.left +
                                                localBox.left,

                                        region.boundingBox.top +
                                                localBox.top,

                                        region.boundingBox.left +
                                                localBox.right,

                                        region.boundingBox.top +
                                                localBox.bottom
                                    )

                                } else {

                                    Rect(
                                        region.boundingBox
                                    )
                                }

                            rawLines +=
                                RecognizedLine(
                                    sourceRegionId =
                                        region.id,

                                    boundingBox =
                                        globalBox,

                                    text =
                                        text,

                                    confidence =
                                        1f
                                )
                        }
                    }

                    /*
                     * Fallback:
                     * preserve OCR result even when ML Kit
                     * did not expose individual line objects.
                     */
                    if (
                        rawLines.isEmpty() &&
                        result.text.isNotBlank()
                    ) {

                        rawLines +=
                            RecognizedLine(
                                sourceRegionId =
                                    region.id,

                                boundingBox =
                                    Rect(
                                        region.boundingBox
                                    ),

                                text =
                                    cleanText(
                                        result.text
                                    ),

                                confidence =
                                    1f
                            )
                    }

                    val normalizedLines =
                        normalizeLinesInsideRegion(
                            rawLines
                        )

                    continuation.resume(
                        normalizedLines
                    )
                }
                .addOnFailureListener { error ->

                    if (continuation.isActive) {

                        continuation.resumeWithException(
                            error
                        )
                    }
                }
        }

    /**
     * Fixes cases where ML Kit splits one physical
     * handwritten row into several OCR fragments.
     *
     * Example:
     *
     * "A binary" + "tree is a hierarchical"
     *
     * They should be ordered left-to-right instead
     * of whichever fragment happens to have the
     * smallest top coordinate.
     */
    private fun normalizeLinesInsideRegion(
        input: List<RecognizedLine>
    ): List<RecognizedLine> {

        if (input.size <= 1) {
            return input
        }

        val medianHeight =
            input
                .map {
                    it.boundingBox
                        .height()
                        .coerceAtLeast(1)
                }
                .sorted()
                .let {
                    it[
                        it.size / 2
                    ]
                }

        val rows =
            mutableListOf<
                    MutableList<RecognizedLine>
                    >()

        val sorted =
            input.sortedWith(
                compareBy(
                    {
                        it.boundingBox
                            .centerY()
                    },
                    {
                        it.boundingBox.left
                    }
                )
            )

        for (line in sorted) {

            val bestRow =
                rows
                    .filter { row ->

                        belongsToSameRow(
                            row = row,
                            candidate = line,
                            medianHeight = medianHeight
                        )
                    }
                    .minByOrNull { row ->

                        val rowCenter =
                            row
                                .map {
                                    it.boundingBox.centerY()
                                }
                                .average()

                        abs(
                            line.boundingBox.centerY() -
                                    rowCenter
                        )
                    }

            if (bestRow == null) {

                rows +=
                    mutableListOf(
                        line
                    )

            } else {

                bestRow +=
                    line
            }
        }

        return rows
            .sortedBy { row ->

                row.minOf {
                    it.boundingBox.top
                }
            }
            .flatMap { row ->

                mergeNearbyFragments(
                    row = row.sortedBy {
                        it.boundingBox.left
                    },
                    medianHeight = medianHeight
                )
            }
    }

    private fun belongsToSameRow(
        row: List<RecognizedLine>,
        candidate: RecognizedLine,
        medianHeight: Int
    ): Boolean {

        val rowTop =
            row.minOf {
                it.boundingBox.top
            }

        val rowBottom =
            row.maxOf {
                it.boundingBox.bottom
            }

        val candidateBox =
            candidate.boundingBox

        val overlapTop =
            max(
                rowTop,
                candidateBox.top
            )

        val overlapBottom =
            min(
                rowBottom,
                candidateBox.bottom
            )

        val overlap =
            max(
                0,
                overlapBottom -
                        overlapTop
            )

        val rowHeight =
            max(
                1,
                rowBottom -
                        rowTop
            )

        val candidateHeight =
            max(
                1,
                candidateBox.height()
            )

        val smallerHeight =
            min(
                rowHeight,
                candidateHeight
            )

        val overlapRatio =
            overlap.toFloat() /
                    smallerHeight.toFloat()

        val rowCenter =
            (
                    rowTop +
                            rowBottom
                    ) / 2

        val centerDistance =
            abs(
                candidateBox.centerY() -
                        rowCenter
            )

        return overlapRatio >= 0.35f ||
                centerDistance <=
                medianHeight * 0.65f
    }

    private fun mergeNearbyFragments(
        row: List<RecognizedLine>,
        medianHeight: Int
    ): List<RecognizedLine> {

        if (row.isEmpty()) {
            return emptyList()
        }

        val result =
            mutableListOf<RecognizedLine>()

        var current =
            row.first()

        for (index in 1 until row.size) {

            val next =
                row[index]

            val gap =
                next.boundingBox.left -
                        current.boundingBox.right

            /*
             * Only merge fragments that are genuinely
             * close together.
             *
             * Large gaps are probably separate columns.
             */
            val canMerge =
                current.sourceRegionId ==
                        next.sourceRegionId &&
                        gap <=
                        medianHeight * 4

            if (canMerge) {

                current =
                    mergeLines(
                        first = current,
                        second = next
                    )

            } else {

                result +=
                    current

                current =
                    next
            }
        }

        result +=
            current

        return result
    }

    private fun mergeLines(
        first: RecognizedLine,
        second: RecognizedLine
    ): RecognizedLine {

        val firstBox =
            first.boundingBox

        val secondBox =
            second.boundingBox

        return RecognizedLine(
            sourceRegionId =
                first.sourceRegionId,

            boundingBox =
                Rect(
                    min(
                        firstBox.left,
                        secondBox.left
                    ),
                    min(
                        firstBox.top,
                        secondBox.top
                    ),
                    max(
                        firstBox.right,
                        secondBox.right
                    ),
                    max(
                        firstBox.bottom,
                        secondBox.bottom
                    )
                ),

            text =
                cleanText(
                    "${first.text} ${second.text}"
                ),

            confidence =
                min(
                    first.confidence,
                    second.confidence
                )
        )
    }

    private fun cleanText(
        text: String
    ): String =
        text
            .trim()
            .replace(
                Regex("\\s+"),
                " "
            )

    override fun close() {
        recognizer.close()
    }
}