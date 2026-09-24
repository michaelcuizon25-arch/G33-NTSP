package com.example.note2snap.utils

import android.graphics.Rect
import com.example.note2snap.model.BlockType
import com.example.note2snap.model.NoteBlock
import com.example.note2snap.model.StructuredNote
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object WhiteboardRuleEngine {

    private data class SpatialCell(
        val text: String,
        val box: Rect
    )

    private data class TableRow(
        val cells: MutableList<SpatialCell> = mutableListOf(),
        var top: Int = 0,
        var bottom: Int = 0
    )

    // =========================================================
    // ML KIT / SPATIAL ENTRY POINT
    // =========================================================

    fun process(
        visionText: Text
    ): StructuredNote {

        val lines = visionText.textBlocks.flatMap { it.lines }

        if (lines.isEmpty()) {
            return StructuredNote(
                title = "Untitled Scan",
                blocks = emptyList()
            )
        }

        val items = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val text = line.text.trim()

            if (text.isBlank()) return@mapNotNull null

            SpatialCell(
                text = text,
                box = Rect(box)
            )
        }.sortedWith(
            compareBy(
                { it.box.top },
                { it.box.left }
            )
        )

        if (items.isEmpty()) {
            return StructuredNote(
                title = "Untitled Scan",
                blocks = emptyList()
            )
        }

        val rawTitle = "Untitled Scan"

        val rows = clusterIntoRows(items)
        val blocks = mutableListOf<NoteBlock>()

        var index = 0

        val maxRight = items.maxOfOrNull { it.box.right } ?: 1000
        val cueColumnSplitX = (maxRight * 0.32).toInt()
        val minBodyLeftX = items.filter { it.box.left > cueColumnSplitX }.minOfOrNull { it.box.left } ?: cueColumnSplitX

        while (index < rows.size) {
            val row = rows[index]
            val rowText = row.cells.joinToString(" ") { it.text }.trim()

            if (rowText.isBlank()) {
                index++
                continue
            }

            val rowBox = getRowBoundingBox(row)

            val leftCueCell = row.cells.find {
                it.box.right <= cueColumnSplitX || isCueQuestion(it.text, cueColumnSplitX, it.box)
            }
            val rightBodyCells = row.cells.filter { it != leftCueCell }

            if (leftCueCell != null && leftCueCell.text.isNotBlank()) {
                val cleanCue = sanitizeOcrText(leftCueCell.text)
                blocks += NoteBlock(
                    rawText = cleanCue,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>${cleanCue}</b>",
                    boundingBox = leftCueCell.box
                )
            }

            val bodyText = if (rightBodyCells.isNotEmpty()) {
                rightBodyCells.joinToString(" ") { it.text }.trim()
            } else if (leftCueCell == null) {
                rowText
            } else {
                ""
            }

            if (bodyText.isNotBlank()) {
                val firstCellLeft = rightBodyCells.firstOrNull()?.box?.left ?: row.cells.first().box.left
                val indentOffset = max(0, firstCellLeft - minBodyLeftX)

                if (isStrongTableRowCandidate(row)) {
                    val tableRows = mutableListOf<TableRow>()
                    var tableIndex = index

                    while (tableIndex < rows.size && isStrongTableRowCandidate(rows[tableIndex])) {
                        tableRows += rows[tableIndex]
                        tableIndex++
                    }

                    if (tableRows.size >= 2) {
                        val htmlTable = buildHtmlTableFromRows(tableRows)
                        val rawTableText = tableRows.joinToString("\n") { r ->
                            r.cells.joinToString(" | ") { it.text }
                        }
                        val tableBox = getRowsBoundingBox(tableRows)

                        blocks += NoteBlock(
                            rawText = rawTableText,
                            type = BlockType.REGULAR_TEXT,
                            formattedText = htmlTable,
                            boundingBox = tableBox
                        )

                        index = tableIndex
                        continue
                    }
                }

                blocks += parseSingleLineBlock(
                    text = bodyText,
                    boundingBox = rowBox,
                    indentOffset = indentOffset
                )
            }

            index++
        }

        return StructuredNote(
            title = rawTitle,
            blocks = blocks
        )
    }

    // =========================================================
    // CUSTOM PIPELINE / RAW STRING ENTRY POINT
    // =========================================================

    fun process(
        rawLines: List<String>
    ): StructuredNote {

        val cleanedLines = rawLines.map { it.trim() }.filter { it.isNotBlank() }

        if (cleanedLines.isEmpty()) {
            return StructuredNote(
                title = "Untitled Scan",
                blocks = emptyList()
            )
        }

        val title = "Untitled Scan"
        val blocks = cleanedLines.map {
            parseSingleLineBlock(text = it, boundingBox = null, indentOffset = 0)
        }

        return StructuredNote(
            title = title,
            blocks = blocks
        )
    }

    // =========================================================
    // OCR SANITIZATION HELPER
    // =========================================================

    private fun sanitizeOcrText(text: String): String {
        var cleaned = text.trim()

        cleaned = cleaned.replace(Regex("[\\u200B\\u00A0]"), " ")

        if (cleaned.matches(Regex("^[oO0◦°cve*\\-•●○▪▸►]\\s*([A-Za-z].*)"))) {
            cleaned = cleaned.replace(Regex("^[oO0◦°cve*\\-•●○▪▸►]\\s*"), "• ")
        }

        return cleaned.trim()
    }

    // =========================================================
    // SINGLE-LINE CLASSIFICATION & HIERARCHICAL BULLETS
    // =========================================================

    private fun parseSingleLineBlock(
        text: String,
        boundingBox: Rect?,
        indentOffset: Int = 0
    ): NoteBlock {

        val trimmed = sanitizeOcrText(text)

        return when {

            isCalloutRule(trimmed) -> {
                val content = trimmed.substringAfter(":").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>NOTE:</b> $content",
                    boundingBox = boundingBox
                )
            }

            isKeyDefinitionRule(trimmed) -> {
                val colonIndex = trimmed.indexOf(":")
                val key = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()

                val bulletPrefix = getBulletSymbol(key)
                val cleanKey = if (bulletPrefix != null) key.removePrefix(bulletPrefix).trim() else key
                val indentPrefix = getIndentSpaces(bulletPrefix, indentOffset)

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.KEY_DEFINITION,
                    formattedText = "$indentPrefix<b>$cleanKey</b>: $value",
                    boundingBox = boundingBox
                )
            }

            isBulletRule(trimmed) -> {
                val symbol = getBulletSymbol(trimmed) ?: "•"
                val cleanText = trimmed.replace(Regex("^([\\-*•●○▪◦▸►])\\s*"), "")
                val indentPrefix = getIndentSpaces(symbol, indentOffset)

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.BULLET_ITEM,
                    formattedText = "$indentPrefix$symbol $cleanText",
                    boundingBox = boundingBox
                )
            }

            isNumberedItemRule(trimmed) -> {
                val match = Regex("^(\\d+)[.)]\\s*(.*)$").find(trimmed)
                val number = match?.groupValues?.getOrNull(1) ?: ""
                val content = match?.groupValues?.getOrNull(2)?.trim() ?: trimmed

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.NUMBERED_ITEM,
                    formattedText = "$number. $content",
                    boundingBox = boundingBox
                )
            }

            isSectionHeaderRule(trimmed) -> {
                val cleanHeader = trimmed.removePrefix("##").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>$cleanHeader</b>",
                    boundingBox = boundingBox
                )
            }

            isSubheadingRule(trimmed) -> {
                val cleanText = trimmed.removePrefix("###").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SUBHEADING,
                    formattedText = "<b>$cleanText</b>",
                    boundingBox = boundingBox
                )
            }

            isMathRule(trimmed) -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.MATHEMATICAL,
                    formattedText = "<i>[Formula]</i> $trimmed",
                    boundingBox = boundingBox
                )
            }

            else -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.REGULAR_TEXT,
                    formattedText = trimmed,
                    boundingBox = boundingBox
                )
            }
        }
    }

    // =========================================================
    // HELPER RULES & PATTERNS
    // =========================================================

    private fun isCueQuestion(text: String, splitX: Int, box: Rect): Boolean {
        if (box.left > splitX * 1.3) return false
        val trimmed = text.trim()
        return trimmed.endsWith("?") ||
                trimmed.startsWith("What is", ignoreCase = true) ||
                trimmed.startsWith("Define", ignoreCase = true) ||
                trimmed.startsWith("How ", ignoreCase = true)
    }

    private fun isCalloutRule(text: String): Boolean {
        return text.startsWith("NOTE:", ignoreCase = true) || text.startsWith("Note:", ignoreCase = true)
    }

    private fun getBulletSymbol(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("◦") || trimmed.startsWith("○") -> "◦"
            trimmed.startsWith("▸") || trimmed.startsWith("►") -> "▸"
            trimmed.startsWith("•") || trimmed.startsWith("●") || trimmed.startsWith("-") || trimmed.startsWith("*") -> "•"
            else -> null
        }
    }

    private fun getIndentSpaces(symbol: String?, indentOffset: Int): String {
        return when {
            symbol == "▸" || indentOffset > 50 -> "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
            symbol == "◦" || indentOffset > 25 -> "&nbsp;&nbsp;&nbsp;&nbsp;"
            else -> ""
        }
    }

    private fun isBulletRule(text: String): Boolean {
        return text.matches(Regex("^([\\-*•●○▪◦▸►])\\s*.+"))
    }

    private fun isNumberedItemRule(text: String): Boolean {
        return text.matches(Regex("^\\d+[.)]\\s*.+"))
    }

    private fun isSectionHeaderRule(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("##")) return true
        if (trimmed.endsWith(":") && trimmed.count { it == ':' } == 1 && trimmed.length in 2..45) return true
        if (trimmed.length in 3..40 && trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }) return true

        if (trimmed.length in 3..35 && !trimmed.endsWith(".") && !trimmed.contains(":") && trimmed.first().isUpperCase() && !isBulletRule(trimmed)) {
            return true
        }

        return false
    }

    private fun isSubheadingRule(text: String): Boolean {
        return text.trim().startsWith("###")
    }

    private fun isKeyDefinitionRule(text: String): Boolean {
        if (text.startsWith("http", ignoreCase = true)) return false
        if (!text.contains(":")) return false
        if (text.trim().endsWith(":")) return false

        val colonIndex = text.indexOf(":")
        if (colonIndex !in 2..45) return false

        val beforeColon = text.substring(0, colonIndex).trim()
        val afterColon = text.substring(colonIndex + 1).trim()

        return beforeColon.isNotBlank() && afterColon.isNotBlank()
    }

    private fun isMathRule(text: String): Boolean {
        val containsOperator = text.contains(Regex("[=≠≈±<>]"))
        val containsNumber = text.any { it.isDigit() }
        return containsOperator && containsNumber
    }

    private fun clusterIntoRows(items: List<SpatialCell>): List<TableRow> {
        val rows = mutableListOf<TableRow>()

        for (item in items) {
            val matchingRow = rows.filter { row ->
                val overlapTop = max(row.top, item.box.top)
                val overlapBottom = min(row.bottom, item.box.bottom)
                val overlap = max(0, overlapBottom - overlapTop)
                val smallerHeight = min(max(1, row.bottom - row.top), max(1, item.box.height()))
                val overlapRatio = overlap.toFloat() / smallerHeight.toFloat()
                overlapRatio >= 0.35f
            }.minByOrNull { row ->
                abs((row.top + row.bottom) / 2 - item.box.centerY())
            }

            if (matchingRow != null) {
                matchingRow.cells += item
                matchingRow.top = min(matchingRow.top, item.box.top)
                matchingRow.bottom = max(matchingRow.bottom, item.box.bottom)
            } else {
                rows += TableRow(
                    cells = mutableListOf(item),
                    top = item.box.top,
                    bottom = item.box.bottom
                )
            }
        }

        rows.forEach { row -> row.cells.sortBy { it.box.left } }
        return rows.sortedBy { it.top }
    }

    private fun isStrongTableRowCandidate(row: TableRow): Boolean {
        if (row.cells.size < 2) return false
        val sorted = row.cells.sortedBy { it.box.left }
        var largeGapCount = 0

        for (i in 0 until sorted.lastIndex) {
            val gap = sorted[i + 1].box.left - sorted[i].box.right
            val averageHeight = (sorted[i].box.height() + sorted[i + 1].box.height()) / 2
            if (gap > max(80, averageHeight * 3)) {
                largeGapCount++
            }
        }
        return largeGapCount > 0
    }

    private fun buildHtmlTableFromRows(rows: List<TableRow>): String {
        val allCells = rows.flatMap { it.cells }
        val columnBounds = detectColumnBounds(allCells)

        if (columnBounds.isEmpty()) {
            return rows.joinToString("<br/>") { row -> row.cells.joinToString(" ") { it.text } }
        }

        val builder = StringBuilder()
        builder.append("<table border='1' style='width:100%;border-collapse:collapse;margin:8px 0;'>")

        rows.forEachIndexed { rowIndex, row ->
            builder.append("<tr>")
            val isHeader = rowIndex == 0
            val rowGrid = Array(columnBounds.size) { StringBuilder() }

            for (cell in row.cells) {
                val columnIndex = getBestColumnIndex(cell.box, columnBounds)
                if (rowGrid[columnIndex].isNotEmpty()) rowGrid[columnIndex].append(" ")
                rowGrid[columnIndex].append(cell.text)
            }

            for (cellText in rowGrid) {
                val value = cellText.toString().trim().ifEmpty { "-" }
                val tag = if (isHeader) "th" else "td"
                val style = if (isHeader) "style='background-color:#F2F2F7;padding:8px;font-weight:bold;text-align:left;color:#000000;'"
                else "style='padding:6px;color:#000000;'"
                builder.append("<$tag $style>$value</$tag>")
            }
            builder.append("</tr>")
        }
        builder.append("</table>")
        return builder.toString()
    }

    private fun detectColumnBounds(cells: List<SpatialCell>): List<Pair<Int, Int>> {
        val sortedLefts = cells.map { it.box.left }.sorted()
        val clusters = mutableListOf<MutableList<Int>>()

        for (left in sortedLefts) {
            val cluster = clusters.find { values -> abs(values.average() - left) < 140 }
            if (cluster != null) {
                cluster += left
            } else {
                clusters += mutableListOf(left)
            }
        }

        return clusters.map { cluster ->
            val minimum = cluster.minOrNull() ?: 0
            val maximum = cluster.maxOrNull() ?: minimum
            Pair(minimum - 20, maximum + 120)
        }.sortedBy { it.first }
    }

    private fun getBestColumnIndex(box: Rect, columns: List<Pair<Int, Int>>): Int {
        if (columns.isEmpty()) return 0
        val center = box.centerX()
        columns.forEachIndexed { index, column ->
            if (center in column.first..column.second) return index
        }
        return columns.indices.minByOrNull { abs(columns[it].first - box.left) } ?: 0
    }

    private fun getRowBoundingBox(row: TableRow): Rect? {
        if (row.cells.isEmpty()) return null
        return Rect(
            row.cells.minOf { it.box.left },
            row.cells.minOf { it.box.top },
            row.cells.maxOf { it.box.right },
            row.cells.maxOf { it.box.bottom }
        )
    }

    private fun getRowsBoundingBox(rows: List<TableRow>): Rect? {
        val cells = rows.flatMap { it.cells }
        if (cells.isEmpty()) return null
        return Rect(
            cells.minOf { it.box.left },
            cells.minOf { it.box.top },
            cells.maxOf { it.box.right },
            cells.maxOf { it.box.bottom }
        )
    }
}