
package com.example.note2snap.structuring

import com.example.note2snap.model.StructuredNote
import com.example.note2snap.recognition.RecognizedLine
import com.example.note2snap.utils.WhiteboardRuleEngine
import kotlin.math.abs
import kotlin.math.max

/**
 * Organizes recognized whiteboard lines before
 * passing them to the rule-based note generator.
 *
 * Coordinates are used for understanding reading
 * order and relationships, NOT for copying the
 * whiteboard's visual layout.
 */
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

        // Determine the correct reading order.
        val orderedLines = orderForReading(lines)

        // Preserve text and coordinates for
        // rule-based reviewer structuring.
        return WhiteboardRuleEngine.processRecognizedLines(
            orderedLines
        )
    }

    /**
     * Determines reading order for:
     * 1. Single-column whiteboards
     * 2. Clearly separated two-column whiteboards
     */
    private fun orderForReading(
        lines: List<RecognizedLine>
    ): List<RecognizedLine> {

        if (lines.size <= 2) {
            return sortByRows(lines)
        }

        val medianHeight = lines
            .map {
                it.boundingBox.height().coerceAtLeast(1)
            }
            .sorted()
            .let {
                it[it.size / 2]
            }

        val minLeft = lines.minOf {
            it.boundingBox.left
        }

        val maxRight = lines.maxOf {
            it.boundingBox.right
        }

        val contentWidth = max(
            1,
            maxRight - minLeft
        )

        val centers = lines
            .map {
                it.boundingBox.centerX()
            }
            .sorted()

        if (centers.size < 4) {
            return sortByRows(lines)
        }

        var biggestGap = 0
        var splitIndex = -1

        for (index in 0 until centers.lastIndex) {

            val gap =
                centers[index + 1] - centers[index]

            if (gap > biggestGap) {
                biggestGap = gap
                splitIndex = index
            }
        }

        val minimumUsefulGap = max(
            medianHeight * 6,
            (contentWidth * 0.18f).toInt()
        )

        if (
            splitIndex < 0 ||
            biggestGap < minimumUsefulGap
        ) {
            return sortByRows(lines)
        }

        val splitX =
            (centers[splitIndex] +
                    centers[splitIndex + 1]) / 2

        val leftColumn = lines.filter {
            it.boundingBox.centerX() < splitX
        }

        val rightColumn = lines.filter {
            it.boundingBox.centerX() >= splitX
        }

        // Avoid treating a stray item as a column.
        if (
            leftColumn.size < 2 ||
            rightColumn.size < 2
        ) {
            return sortByRows(lines)
        }

        val leftTop = leftColumn.minOf {
            it.boundingBox.top
        }

        val rightTop = rightColumn.minOf {
            it.boundingBox.top
        }

        val columnStartDifference =
            abs(leftTop - rightTop)

        // Read one column completely before the
        // other if their starting positions differ.
        if (
            columnStartDifference >
            medianHeight * 4
        ) {
            return if (leftTop < rightTop) {
                sortByRows(leftColumn) +
                        sortByRows(rightColumn)
            } else {
                sortByRows(rightColumn) +
                        sortByRows(leftColumn)
            }
        }

        return sortByRows(lines)
    }

    /**
     * Groups nearby line fragments into rows,
     * then orders each row from left to right.
     */
    private fun sortByRows(
        lines: List<RecognizedLine>
    ): List<RecognizedLine> {

        if (lines.size <= 1) {
            return lines
        }

        val medianHeight = lines
            .map {
                it.boundingBox.height().coerceAtLeast(1)
            }
            .sorted()
            .let {
                it[it.size / 2]
            }

        val rows =
            mutableListOf<MutableList<RecognizedLine>>()

        val sorted = lines.sortedBy {
            it.boundingBox.centerY()
        }

        for (line in sorted) {

            val matchingRow = rows
                .filter { row ->

                    val rowCenter = row
                        .map {
                            it.boundingBox.centerY()
                        }
                        .average()

                    abs(
                        line.boundingBox.centerY() -
                                rowCenter
                    ) <= medianHeight * 0.70f
                }
                .minByOrNull { row ->

                    val rowCenter = row
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
                rows.add(
                    mutableListOf(line)
                )
            } else {
                matchingRow.add(line)
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
}
