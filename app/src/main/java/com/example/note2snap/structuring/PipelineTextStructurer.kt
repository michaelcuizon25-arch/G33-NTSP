package com.example.note2snap.structuring

import android.graphics.Rect
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.recognition.RecognizedLine
import com.example.note2snap.utils.WhiteboardRuleEngine
import kotlin.math.abs
import kotlin.math.max

class PipelineTextStructurer {

    fun structure(
        lines: List<RecognizedLine>
    ): StructuredNote {

        if (lines.isEmpty()) {

            return StructuredNote(
                title = "Untitled Scan",
                blocks = emptyList()
            )
        }

        /*
         * Do NOT simply sort by:
         *
         * top -> left
         *
         * because a whiteboard may contain two columns.
         *
         * Example:
         *
         * Properties       Traversals
         * bullet 1         Preorder
         * bullet 2         Inorder
         *
         * A top-only sort would alternate both columns.
         */
        val orderedLines =
            orderForReading(
                lines
            )

        val structured =
            WhiteboardRuleEngine.process(
                orderedLines.map {
                    it.text
                }
            )

        /*
         * WhiteboardRuleEngine uses the first
         * meaningful line as the note title.
         *
         * Title is not included in blocks.
         */
        val remainingLines =
            orderedLines
                .drop(1)
                .toMutableList()

        val blocksWithCoordinates =
            structured.blocks.map { block ->

                val blockText =
                    normalizeText(
                        block.rawText.ifBlank {

                            removeHtml(
                                block.formattedText
                            )
                        }
                    )

                val matchedIndex =
                    remainingLines.indexOfFirst { line ->

                        val lineText =
                            normalizeText(
                                line.text
                            )

                        lineText.isNotBlank() &&
                                blockText.isNotBlank() &&
                                (
                                        lineText ==
                                                blockText ||

                                                lineText.contains(
                                                    blockText
                                                ) ||

                                                blockText.contains(
                                                    lineText
                                                )
                                        )
                    }

                val matchedLine =
                    if (matchedIndex >= 0) {

                        remainingLines.removeAt(
                            matchedIndex
                        )

                    } else if (
                        remainingLines.isNotEmpty()
                    ) {

                        remainingLines.removeAt(
                            0
                        )

                    } else {

                        null
                    }

                block.copy(
                    boundingBox =
                        matchedLine?.let {

                            Rect(
                                it.boundingBox
                            )
                        }
                )
            }

        return structured.copy(
            blocks =
                blocksWithCoordinates
        )
    }

    /**
     * Determines reading order for either:
     *
     * 1. Normal single-column notes
     * 2. Clearly separated two-column whiteboards
     *
     * It intentionally avoids blindly sorting
     * everything by top coordinate.
     */
    private fun orderForReading(
        lines: List<RecognizedLine>
    ): List<RecognizedLine> {

        if (lines.size <= 2) {

            return sortByRows(
                lines
            )
        }

        val medianHeight =
            lines
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

        val minLeft =
            lines.minOf {
                it.boundingBox.left
            }

        val maxRight =
            lines.maxOf {
                it.boundingBox.right
            }

        val contentWidth =
            max(
                1,
                maxRight -
                        minLeft
            )

        val centers =
            lines
                .map {
                    it.boundingBox.centerX()
                }
                .sorted()

        if (centers.size < 4) {

            return sortByRows(
                lines
            )
        }

        var biggestGap =
            0

        var splitIndex =
            -1

        for (
        index in
        0 until centers.lastIndex
        ) {

            val gap =
                centers[index + 1] -
                        centers[index]

            if (gap > biggestGap) {

                biggestGap =
                    gap

                splitIndex =
                    index
            }
        }

        val minimumUsefulGap =
            max(
                medianHeight * 6,
                (contentWidth * 0.18f)
                    .toInt()
            )

        /*
         * No clear column separation:
         * use ordinary row-based reading.
         */
        if (
            splitIndex < 0 ||
            biggestGap <
            minimumUsefulGap
        ) {

            return sortByRows(
                lines
            )
        }

        val splitX =
            (
                    centers[splitIndex] +
                            centers[
                                splitIndex + 1
                            ]
                    ) / 2

        val leftColumn =
            lines.filter {
                it.boundingBox.centerX() <
                        splitX
            }

        val rightColumn =
            lines.filter {
                it.boundingBox.centerX() >=
                        splitX
            }

        /*
         * Do not treat a single stray item as
         * an entire second column.
         */
        if (
            leftColumn.size < 2 ||
            rightColumn.size < 2
        ) {

            return sortByRows(
                lines
            )
        }

        val leftTop =
            leftColumn.minOf {
                it.boundingBox.top
            }

        val rightTop =
            rightColumn.minOf {
                it.boundingBox.top
            }

        val columnStartDifference =
            abs(
                leftTop -
                        rightTop
            )

        /*
         * If one column clearly begins much later,
         * treat it as a secondary section.
         *
         * This matches boards such as:
         *
         * Properties      Traversals
         *
         * where Traversals starts lower on the
         * whiteboard and should not interrupt the
         * Properties bullet list.
         */
        if (
            columnStartDifference >
            medianHeight * 4
        ) {

            return if (
                leftTop <
                rightTop
            ) {

                sortByRows(
                    leftColumn
                ) +
                        sortByRows(
                            rightColumn
                        )

            } else {

                sortByRows(
                    rightColumn
                ) +
                        sortByRows(
                            leftColumn
                        )
            }
        }

        /*
         * Both columns begin at approximately the
         * same height, so preserve normal spatial
         * top-to-bottom reading instead.
         */
        return sortByRows(
            lines
        )
    }

    /**
     * Groups nearby Y coordinates into one row,
     * then reads each row left-to-right.
     *
     * This prevents:
     *
     * "tree is a hierarchical"
     * appearing before
     * "A binary"
     *
     * simply because one fragment is a few pixels
     * higher than the other.
     */
    private fun sortByRows(
        lines: List<RecognizedLine>
    ): List<RecognizedLine> {

        if (lines.size <= 1) {
            return lines
        }

        val medianHeight =
            lines
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
            lines.sortedBy {
                it.boundingBox.centerY()
            }

        for (line in sorted) {

            val matchingRow =
                rows
                    .filter { row ->

                        val rowCenter =
                            row
                                .map {
                                    it.boundingBox.centerY()
                                }
                                .average()

                        abs(
                            line.boundingBox.centerY() -
                                    rowCenter
                        ) <=
                                medianHeight * 0.70f
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

            if (matchingRow == null) {

                rows +=
                    mutableListOf(
                        line
                    )

            } else {

                matchingRow +=
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

                row.sortedBy {
                    it.boundingBox.left
                }
            }
    }

    private fun normalizeText(
        text: String
    ): String =
        text
            .lowercase()
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()

    private fun removeHtml(
        text: String
    ): String =
        text
            .replace(
                Regex("<[^>]*>"),
                " "
            )
            .replace(
                "&nbsp;",
                " "
            )
            .trim()
}