package com.example.note2snap.utils

import android.graphics.Rect
import android.text.TextUtils
import com.example.note2snap.model.BlockType
import com.example.note2snap.model.NoteBlock
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.recognition.RecognizedLine
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns recognized whiteboard / notebook lines (with their positions) into a
 * structured note.
 *
 * Understands:
 *  - a document title on the top line ("1.1 - Introduction to Statistics")
 *  - Cornell layouts: a left column of cue questions next to the notes
 *  - short body headings ("Scientific Method", "Census", "Sample (n)")
 *  - bullets / sub-bullets / numbered lists, with indentation levels measured
 *    from the photo itself
 *  - wrapped lines (joined back into one sentence)
 *  - key: definition lines, NOTE: callouts, formulas ("n <= N")
 *  - tables, and handwritten margin notes that must NOT become tables
 *
 * Every distance is measured relative to the text size in the photo, so it
 * works at any image resolution.
 */
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

    /** Text-size reference measured from the photo. [unit] = typical line height. */
    private class LayoutMetrics(
        val unit: Int,
        val boardLeft: Int,
        val boardRight: Int
    ) {
        val boardWidth: Int
            get() = max(1, boardRight - boardLeft)
    }

    private class Cue(var text: String, var box: Rect)

    private class MarginGroup(val text: String, val top: Int, val bottom: Int, val box: Rect)

    private class BodyLine(var text: String, var box: Rect)

    private sealed class BodyItem {
        class Line(val line: BodyLine) : BodyItem()
        class Done(val block: NoteBlock) : BodyItem()
    }

    private class Entry(
        val block: NoteBlock,
        val level: Int,
        val top: Int,
        val bottom: Int,
        val plain: Boolean
    )

    private class Insertion(
        val index: Int,
        val priority: Int,
        val block: NoteBlock,
        val isCue: Boolean = false
    )

    /**
     * OPTIONAL project-specific OCR corrections (regex -> replacement).
     * Empty by default; fill it from your own recognizer's real mistakes, e.g.
     * WhiteboardRuleEngine.ocrCorrections = listOf(Regex("\\bteh\\b") to "the")
     */
    @Volatile
    var ocrCorrections: List<Pair<Regex, String>> = emptyList()

    /**
     * Cornell-style output: every cue question is placed in a left column
     * next to the notes it belongs to (like the original page). Set to false
     * to get the old flat list where cues are plain bold lines.
     */
    @Volatile
    var cornellLayout: Boolean = true

    // =========================================================
    // ENTRY POINTS
    // =========================================================

    /** ML Kit text result. */
    fun process(visionText: Text): StructuredNote {
        val items = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.isBlank()) return@mapNotNull null
                SpatialCell(text, Rect(box))
            }

        return processCells(items)
    }

    /**
     * Output of CrnnLineRecognizer. Keeps every line's bounding box, so cue
     * columns, indentation and tables work exactly like the ML Kit path.
     * (Named differently because List<String> and List<RecognizedLine> have
     * the same JVM signature.)
     */
    fun processRecognizedLines(recognizedLines: List<RecognizedLine>): StructuredNote {
        val items = recognizedLines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isBlank()) return@mapNotNull null
            SpatialCell(text, Rect(line.boundingBox))
        }

        return processCells(items)
    }

    /** Plain strings without positions (no columns / indentation available). */
    fun process(rawLines: List<String>): StructuredNote {
        val cleaned = rawLines.map { it.trim() }.filter { it.isNotBlank() }

        if (cleaned.isEmpty()) return emptyNote()

        val blocks = cleaned.mapIndexed { index, text ->
            val next = cleaned.getOrNull(index + 1)
            parseSingleLineBlock(
                text = text,
                boundingBox = null,
                level = 0,
                headingLike = headingLikeText(text, next, false)
            )
        }

        return StructuredNote(title = fallbackTitle(blocks), blocks = blocks)
    }

    private fun emptyNote() = StructuredNote(title = "Untitled Scan", blocks = emptyList())

    // =========================================================
    // MAIN PIPELINE
    // =========================================================

    private fun processCells(allItems: List<SpatialCell>): StructuredNote {
        if (allItems.isEmpty()) return emptyNote()

        // 1. Document title (top line standing alone)
        val titleCell = findTitleCell(allItems)
        val items = if (titleCell == null) allItems else allItems.filter { it !== titleCell }
        val titleText = titleCell?.let {
            sanitizeOcrText(it.text).replace(Regex("^#+\\s*"), "")
        }

        if (items.isEmpty()) {
            return StructuredNote(title = titleText ?: "Untitled Scan", blocks = emptyList())
        }

        // 2. Measure the photo and find the cue column (if any)
        val metrics = measureLayout(items)
        val splitX = findCueGutter(items, metrics)

        val cueLimit = if (splitX == null) Int.MIN_VALUE else splitX + metrics.unit / 2
        var cueItems = items.filter { it.box.right <= cueLimit }
        var bodyItems = items.filter { it.box.right > cueLimit }

        if (bodyItems.isEmpty()) {
            cueItems = emptyList()
            bodyItems = items
        }

        val cues = mergeCues(cueItems, metrics)

        // 3. Body geometry + handwritten margin notes
        val bodyLeft = bodyItems.minOf { it.box.left }
        val bodyRight = percentileRight(bodyItems, 0.85)
        val bodyWidth = max(1, bodyRight - bodyLeft)

        val (mainCandidates, marginCandidates) =
            splitMarginCells(bodyItems, bodyLeft, bodyWidth, metrics)

        val mainItems = if (mainCandidates.isEmpty()) bodyItems else mainCandidates
        val marginItems = if (mainCandidates.isEmpty()) emptyList() else marginCandidates

        val margins = groupMargins(marginItems, metrics)

        // 4. Rows -> lines/tables -> join wrapped lines
        val rows = clusterIntoRows(mainItems)
        val sequence = buildBodyItems(rows, metrics)
        val merged = mergeContinuations(sequence, metrics, bodyWidth)

        // 5. Indent levels measured from the photo
        val bodyLines = merged.filterIsInstance<BodyItem.Line>().map { it.line }
        val levelStarts = computeLevelStarts(bodyLines, metrics)

        // 6. Classify each line
        val entries = mutableListOf<Entry>()

        for ((index, item) in merged.withIndex()) {
            when (item) {
                is BodyItem.Done -> {
                    val box = item.block.boundingBox
                    entries += Entry(
                        block = item.block,
                        level = 0,
                        top = box?.top ?: 0,
                        bottom = box?.bottom ?: 0,
                        plain = false
                    )
                }

                is BodyItem.Line -> {
                    val line = item.line
                    val level = levelFor(line.box.left, levelStarts)
                    val next = (merged.getOrNull(index + 1) as? BodyItem.Line)?.line
                    val nextIndented =
                        next != null && next.box.left > line.box.left + metrics.unit * 0.6

                    val block = parseSingleLineBlock(
                        text = line.text,
                        boundingBox = Rect(line.box),
                        level = level,
                        headingLike = headingLikeText(line.text, next?.text, nextIndented)
                    )

                    entries += Entry(
                        block = block,
                        level = level,
                        top = line.box.top,
                        bottom = line.box.bottom,
                        plain = block.type == BlockType.REGULAR_TEXT
                    )
                }
            }
        }

        // 7. Plain lines inside a list become bullets (bullet glyph often not read)
        val finalEntries = promotePlainItems(entries, metrics)

        // 8. Place cue questions and margin notes next to the right section
        val insertions = mutableListOf<Insertion>()

        for (cue in cues) {
            insertions += Insertion(
                index = cueInsertIndex(cue, finalEntries, metrics),
                priority = 1,
                block = NoteBlock(
                    rawText = cue.text,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>${esc(cue.text)}</b>",
                    boundingBox = cue.box
                ),
                isCue = true
            )
        }

        for (group in margins) {
            val index = finalEntries.indexOfLast { it.top <= group.bottom } + 1
            insertions += Insertion(
                index = index,
                priority = 0,
                block = NoteBlock(
                    rawText = "Margin note: ${group.text}",
                    type = BlockType.REGULAR_TEXT,
                    formattedText = "<i>[Margin note] ${esc(group.text)}</i>",
                    boundingBox = group.box
                )
            )
        }

        val orderedInsertions = insertions.sortedWith(compareBy({ it.index }, { it.priority }))

        val blocks = mutableListOf<NoteBlock>()

        if (titleCell != null && titleText != null) {
            blocks += NoteBlock(
                rawText = titleText,
                type = BlockType.SECTION_HEADER,
                formattedText = "<big><b>${esc(titleText)}</b></big>",
                boundingBox = Rect(titleCell.box)
            )
        }

        val cueBlocks = mutableListOf<NoteBlock>()
        var nextInsertion = 0
        for (i in 0..finalEntries.size) {
            while (
                nextInsertion < orderedInsertions.size &&
                orderedInsertions[nextInsertion].index == i
            ) {
                val insertion = orderedInsertions[nextInsertion]
                blocks += insertion.block
                if (insertion.isCue) cueBlocks += insertion.block
                nextInsertion++
            }
            if (i < finalEntries.size) blocks += finalEntries[i].block
        }

        val finalBlocks =
            if (cornellLayout && cueBlocks.isNotEmpty()) groupCornell(blocks, cueBlocks)
            else blocks

        return StructuredNote(
            title = titleText ?: fallbackTitle(blocks),
            blocks = finalBlocks
        )
    }

    // =========================================================
    // CORNELL LAYOUT (cue column | notes)
    // =========================================================

    /**
     * Merges each cue question with the blocks that follow it (until the next
     * cue or a NOTE callout) into one two-column row: cue on the left, notes
     * on the right. Blocks before the first cue (e.g. the title) and NOTE
     * callouts stay full width, like the bottom of the original page.
     */
    private fun groupCornell(blocks: List<NoteBlock>, cueBlocks: List<NoteBlock>): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        var cue: NoteBlock? = null
        val body = mutableListOf<NoteBlock>()

        fun flush() {
            val current = cue ?: return
            out += if (body.isEmpty()) current else buildCornellRow(current, body)
            cue = null
            body.clear()
        }

        for (block in blocks) {
            val isCue = cueBlocks.any { it === block }
            val isCallout = block.type == BlockType.SECTION_HEADER &&
                    block.rawText.startsWith("NOTE", ignoreCase = true)

            when {
                isCue -> {
                    flush()
                    cue = block
                }

                isCallout -> {
                    flush()
                    out += block
                }

                cue != null -> body += block
                else -> out += block
            }
        }

        flush()
        return out
    }

    private fun buildCornellRow(cue: NoteBlock, body: List<NoteBlock>): NoteBlock {
        val right = body.joinToString("<br/>") { it.formattedText.orEmpty() }

        val html = "<table style='width:100%;border-collapse:collapse;margin:6px 0;'><tr>" +
                "<td style='width:30%;vertical-align:top;padding:6px 10px 6px 0;" +
                "border-right:1px solid #C7C7CC;'>${cue.formattedText.orEmpty()}</td>" +
                "<td style='vertical-align:top;padding:6px 0 6px 12px;'>$right</td>" +
                "</tr></table>"

        val boxes = (listOf(cue) + body).mapNotNull { it.boundingBox }
        val union = boxes.takeIf { it.isNotEmpty() }?.map { Rect(it) }?.reduce { a, b -> unionRect(a, b) }

        return NoteBlock(
            rawText = cue.rawText + "\n" + body.joinToString("\n") { it.rawText },
            type = BlockType.REGULAR_TEXT,
            formattedText = html,
            boundingBox = union
        )
    }

    private fun fallbackTitle(blocks: List<NoteBlock>): String {
        val candidates = blocks.filter {
            (it.type == BlockType.SECTION_HEADER || it.type == BlockType.SUBHEADING) &&
                    !it.rawText.startsWith("NOTE", ignoreCase = true)
        }

        val chosen = candidates.firstOrNull { !it.rawText.trim().endsWith("?") }
            ?: candidates.firstOrNull()

        return chosen?.rawText?.replace(Regex("^#+\\s*"), "") ?: "Untitled Scan"
    }

    // =========================================================
    // LAYOUT ANALYSIS
    // =========================================================

    private fun measureLayout(items: List<SpatialCell>): LayoutMetrics {
        val heights = items.map { it.box.height() }.filter { it > 0 }.sorted()
        val median = if (heights.isEmpty()) 30 else heights[heights.size / 2]

        return LayoutMetrics(
            unit = max(8, median),
            boardLeft = items.minOf { it.box.left },
            boardRight = items.maxOf { it.box.right }
        )
    }

    private fun percentileRight(items: List<SpatialCell>, p: Double): Int {
        val rights = items.map { it.box.right }.sorted()
        return rights[((rights.size - 1) * p).toInt()]
    }

    private fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val smaller = min(max(1, a.height()), max(1, b.height()))
        return overlap.toFloat() / smaller.toFloat()
    }

    private fun unionRect(a: Rect, b: Rect) = Rect(
        min(a.left, b.left),
        min(a.top, b.top),
        max(a.right, b.right),
        max(a.bottom, b.bottom)
    )

    /**
     * Finds the empty vertical strip (gutter) between a left column of cue
     * questions and the notes. Handles a title line crossing the gutter.
     * Returns the x position of the gutter, or null if the board has no
     * cue column (a normal single-column board).
     */
    private fun findCueGutter(items: List<SpatialCell>, metrics: LayoutMetrics): Int? {
        if (items.size < 4) return null

        val width = metrics.boardWidth
        val from = metrics.boardLeft + (width * 0.10).toInt()
        val to = metrics.boardLeft + (width * 0.55).toInt()
        val step = max(1, metrics.unit / 2)
        val allowedCover = max(1, (items.size * 0.06).toInt())

        var bestStart = -1
        var bestEnd = -1
        var runStart = -1

        var x = from
        while (x <= to + step) {
            val inRange = x <= to
            val cover =
                if (inRange) items.count { it.box.left < x && it.box.right > x }
                else Int.MAX_VALUE

            if (inRange && cover <= allowedCover) {
                if (runStart < 0) runStart = x
            } else if (runStart >= 0) {
                val runEnd = x - step
                if (runEnd - runStart > bestEnd - bestStart) {
                    bestStart = runStart
                    bestEnd = runEnd
                }
                runStart = -1
            }

            x += step
        }

        if (bestStart < 0 || bestEnd - bestStart < metrics.unit) return null

        val split = (bestStart + bestEnd) / 2
        val leftCount = items.count { it.box.right <= split }
        val rightCount = items.count { it.box.left >= split }

        // The cue column is the narrow side.
        if (leftCount < 2 || rightCount < 3 || leftCount > rightCount) return null

        // The notes beside a cue column contain long lines. In a table every
        // cell is short, so its first column must not be taken as cues.
        val longLines = items.count {
            it.box.left >= split && it.box.width() >= width * 0.25
        }
        if (longLines < 2) return null

        return split
    }

    /** Top line standing alone = document title. */
    private fun findTitleCell(items: List<SpatialCell>): SpatialCell? {
        if (items.size < 3) return null

        val top = items.minByOrNull { it.box.top } ?: return null
        val text = top.text.trim()

        if (text.length !in 3..70) return null
        if (".,;!?:".contains(text.last())) return null
        if (isBulletRule(text) || isCalloutRule(text) || isQuestionHeader(text)) return null
        if (isNumberedItemRule(text) || isKeyDefinitionRule(text)) return null

        val sharesRow = items.any {
            it !== top && verticalOverlapRatio(it.box, top.box) >= 0.35f
        }
        if (sharesRow) return null

        return top
    }

    /** Merges wrapped cue-column lines ("What is the difference / between ... / a sample?"). */
    private fun mergeCues(cells: List<SpatialCell>, metrics: LayoutMetrics): List<Cue> {
        val result = mutableListOf<Cue>()

        for (cell in cells.sortedBy { it.box.top }) {
            val text = sanitizeOcrText(cell.text)
            if (text.isBlank()) continue

            val last = result.lastOrNull()

            if (
                last != null &&
                !last.text.trimEnd().endsWith("?") &&
                cell.box.top - last.box.bottom <= metrics.unit * 0.9 &&
                abs(cell.box.left - last.box.left) <= metrics.unit * 2
            ) {
                last.text = last.text + " " + text
                last.box = unionRect(last.box, cell.box)
            } else {
                result += Cue(text, Rect(cell.box))
            }
        }

        return result
    }

    /**
     * Handwritten notes in the right margin (e.g. a small "Population -> (N)"
     * sketch). They must not be glued onto the sentence beside them and must
     * not turn those rows into a fake table.
     */
    private fun splitMarginCells(
        items: List<SpatialCell>,
        bodyLeft: Int,
        bodyWidth: Int,
        metrics: LayoutMetrics
    ): Pair<List<SpatialCell>, List<SpatialCell>> {
        val marginStart = bodyLeft + (bodyWidth * 0.62).toInt()
        val main = mutableListOf<SpatialCell>()
        val margin = mutableListOf<SpatialCell>()

        for (cell in items) {
            val farRight = cell.box.left >= marginStart && cell.box.width() <= bodyWidth * 0.40

            if (!farRight) {
                main += cell
                continue
            }

            val leftNeighbors = items.filter {
                it !== cell &&
                        it.box.right <= cell.box.left &&
                        verticalOverlapRatio(it.box, cell.box) >= 0.35f
            }

            val farGap =
                leftNeighbors.isEmpty() ||
                        cell.box.left - leftNeighbors.maxOf { it.box.right } > metrics.unit * 4

            // A real table has short cells; a sentence next to a far-right
            // scribble means the scribble is a margin note.
            val sentenceNeighbor =
                leftNeighbors.isEmpty() || leftNeighbors.sumOf { it.text.length } >= 20

            if (farGap && sentenceNeighbor) margin += cell else main += cell
        }

        return Pair(main, margin)
    }

    private fun groupMargins(cells: List<SpatialCell>, metrics: LayoutMetrics): List<MarginGroup> {
        if (cells.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<SpatialCell>>()

        for (cell in cells.sortedBy { it.box.top }) {
            val current = groups.lastOrNull()
            if (current != null && cell.box.top - current.maxOf { it.box.bottom } <= metrics.unit * 4) {
                current += cell
            } else {
                groups += mutableListOf(cell)
            }
        }

        return groups.map { group ->
            val box = group.map { it.box }.reduce { a, b -> unionRect(a, b) }
            MarginGroup(
                text = group.joinToString(" · ") { sanitizeOcrText(it.text) },
                top = box.top,
                bottom = box.bottom,
                box = box
            )
        }
    }

    // =========================================================
    // ROWS, TABLES, WRAPPED LINES
    // =========================================================

    private fun clusterIntoRows(items: List<SpatialCell>): List<TableRow> {
        val rows = mutableListOf<TableRow>()

        for (item in items) {
            val matchingRow = rows.filter { row ->
                val overlapTop = max(row.top, item.box.top)
                val overlapBottom = min(row.bottom, item.box.bottom)
                val overlap = max(0, overlapBottom - overlapTop)
                val smallerHeight = min(max(1, row.bottom - row.top), max(1, item.box.height()))
                overlap.toFloat() / smallerHeight.toFloat() >= 0.35f
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

    private fun isStrongTableRowCandidate(row: TableRow, metrics: LayoutMetrics): Boolean {
        if (row.cells.size < 2) return false
        if (row.cells.any { it.text.length > 80 }) return false

        val sorted = row.cells.sortedBy { it.box.left }
        var largeGapCount = 0

        for (i in 0 until sorted.lastIndex) {
            val gap = sorted[i + 1].box.left - sorted[i].box.right
            val averageHeight = (sorted[i].box.height() + sorted[i + 1].box.height()) / 2

            if (gap > max(metrics.unit * 2, averageHeight * 3)) {
                largeGapCount++
            }
        }

        return largeGapCount > 0
    }

    /** Neighbouring table rows must share column positions. */
    private fun columnsAlign(a: TableRow, b: TableRow, metrics: LayoutMetrics): Boolean {
        val tolerance = metrics.unit * 3
        val matches = b.cells.count { cell ->
            a.cells.any { abs(it.box.left - cell.box.left) <= tolerance }
        }
        return matches >= 2
    }

    private fun buildBodyItems(rows: List<TableRow>, metrics: LayoutMetrics): List<BodyItem> {
        val out = mutableListOf<BodyItem>()
        var i = 0

        while (i < rows.size) {
            val row = rows[i]

            if (isStrongTableRowCandidate(row, metrics)) {
                var j = i + 1
                while (
                    j < rows.size &&
                    isStrongTableRowCandidate(rows[j], metrics) &&
                    columnsAlign(rows[j - 1], rows[j], metrics)
                ) {
                    j++
                }

                if (j - i >= 2) {
                    val tableRows = rows.subList(i, j)
                    val rawTableText = tableRows.joinToString("\n") { r ->
                        r.cells.joinToString(" | ") { it.text }
                    }

                    out += BodyItem.Done(
                        NoteBlock(
                            rawText = rawTableText,
                            type = BlockType.REGULAR_TEXT,
                            formattedText = buildHtmlTableFromRows(tableRows, metrics),
                            boundingBox = getRowsBoundingBox(tableRows)
                        )
                    )

                    i = j
                    continue
                }
            }

            val text = row.cells.sortedBy { it.box.left }.joinToString(" ") { it.text }.trim()
            val box = getRowBoundingBox(row)

            if (text.isNotBlank() && box != null) {
                out += BodyItem.Line(BodyLine(text, box))
            }

            i++
        }

        return out
    }

    /**
     * Joins lines that are only the visual wrap of the previous sentence
     * ("... one wishes to better" / "understand certain characteristics ...").
     */
    private fun mergeContinuations(
        items: List<BodyItem>,
        metrics: LayoutMetrics,
        bodyWidth: Int
    ): List<BodyItem> {
        val out = mutableListOf<BodyItem>()

        for (item in items) {
            val prev = out.lastOrNull()

            if (
                item is BodyItem.Line &&
                prev is BodyItem.Line &&
                canMerge(prev.line, item.line, metrics, bodyWidth)
            ) {
                prev.line.text = prev.line.text.trimEnd() + " " + item.line.text.trim()
                prev.line.box = unionRect(prev.line.box, item.line.box)
            } else {
                out += item
            }
        }

        return out
    }

    private fun canMerge(
        prev: BodyLine,
        cur: BodyLine,
        metrics: LayoutMetrics,
        bodyWidth: Int
    ): Boolean {
        val prevText = prev.text.trim()
        val curText = cur.text.trim()

        if (prevText.isEmpty() || curText.isEmpty()) return false
        if (isMergeBreaker(curText)) return false
        if (".!?:;".contains(prevText.last())) return false

        // Wrapped lines sit directly under each other.
        if (cur.box.top - prev.box.bottom > metrics.unit * 0.7) return false
        if (cur.box.left < prev.box.left - metrics.unit * 0.5) return false

        // A wrapped line starts near the line above it. A line pushed far to
        // the right is a new item (e.g. a centred formula such as "n <= N").
        if (cur.box.left > prev.box.left + metrics.unit * 2.5) return false

        // Short formula lines and "o item" sub-bullets are their own lines.
        if (curText.length <= 30 && isMathRule(curText)) return false
        if (curText.matches(Regex("^o\\s+\\S.*")) && cur.box.left > prev.box.left + metrics.unit * 0.6) return false

        // The line above must have been (nearly) full width. This keeps a
        // short heading or list item from being glued to the line below it.
        if (prev.box.width() < bodyWidth * 0.5) return false

        // ...and the new line must continue the sentence. Handwritten wraps
        // start in lowercase; an uppercase start is a new item unless the line
        // above stopped mid-sentence (comma, hyphen, or a connecting word).
        return curText.first().isLowerCase() || endsMidSentence(prevText)
    }

    private val connectorWords = setOf(
        "a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "from", "with",
        "and", "or", "but", "as", "that", "which", "is", "are", "was", "were", "be"
    )

    private fun endsMidSentence(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.endsWith(",") || trimmed.endsWith("-")) return true

        val lastWord = trimmed.substringAfterLast(' ').lowercase()
        return lastWord in connectorWords
    }

    private fun isMergeBreaker(text: String): Boolean {
        return isBulletRule(text) ||
                isNumberedItemRule(text) ||
                isSectionNumberHeader(text) ||
                isCalloutRule(text) ||
                isQuestionHeader(text) ||
                text.startsWith("#") ||
                (text.length in 3..45 && text == text.uppercase() && text.any { it.isLetter() })
    }

    // =========================================================
    // INDENT LEVELS
    // =========================================================

    /**
     * Learns the real indent positions of this photo (e.g. 210 / 245 / 275 px)
     * instead of assuming fixed pixel offsets.
     */
    private fun computeLevelStarts(lines: List<BodyLine>, metrics: LayoutMetrics): List<Int> {
        val tolerance = metrics.unit * 1.5
        val starts = mutableListOf<Int>()

        for (left in lines.map { it.box.left }.sorted()) {
            if (starts.isEmpty() || left - starts.last() > tolerance) {
                starts += left
            }
        }

        return starts
    }

    private fun levelFor(left: Int, starts: List<Int>): Int {
        return starts.indexOfLast { it <= left }.coerceIn(0, 3)
    }

    private fun indentSpaces(level: Int): String {
        return "&nbsp;&nbsp;&nbsp;&nbsp;".repeat(level.coerceIn(0, 3))
    }

    private fun bulletForLevel(level: Int): String {
        return when {
            level <= 0 -> "•"
            level == 1 -> "◦"
            else -> "▪"
        }
    }

    // =========================================================
    // PLACING CUES / PROMOTING LIST ITEMS
    // =========================================================

    private fun isListType(type: BlockType): Boolean {
        return type == BlockType.BULLET_ITEM ||
                type == BlockType.KEY_DEFINITION ||
                type == BlockType.NUMBERED_ITEM
    }

    /**
     * A plain line indented inside a list, or directly under a list item at
     * the same level, is a bullet whose symbol was not read by the OCR.
     */
    private fun promotePlainItems(entries: List<Entry>, metrics: LayoutMetrics): List<Entry> {
        val out = mutableListOf<Entry>()

        for (entry in entries) {
            if (!entry.plain) {
                out += entry
                continue
            }

            val prev = out.lastOrNull()
            val text = entry.block.rawText
            val startsLikeItem = text.firstOrNull()?.let { it.isUpperCase() || it.isDigit() } == true

            val siblingOfList =
                prev != null &&
                        isListType(prev.block.type) &&
                        prev.level == entry.level &&
                        entry.top - prev.bottom <= metrics.unit * 1.6 &&
                        startsLikeItem

            if (entry.level >= 1 || siblingOfList) {
                out += Entry(
                    block = NoteBlock(
                        rawText = text,
                        type = BlockType.BULLET_ITEM,
                        formattedText = "${indentSpaces(entry.level)}${bulletForLevel(entry.level)} ${esc(text)}",
                        boundingBox = entry.block.boundingBox
                    ),
                    level = entry.level,
                    top = entry.top,
                    bottom = entry.bottom,
                    plain = false
                )
            } else {
                out += entry
            }
        }

        return out
    }

    /**
     * Where a cue question goes: right before the section it sits beside.
     * If the cue sits beside a nested sub-bullet (not a section start), it
     * belongs to the next heading just below it.
     */
    private fun cueInsertIndex(cue: Cue, entries: List<Entry>, metrics: LayoutMetrics): Int {
        val threshold = cue.box.top - (metrics.unit * 1.2).toInt()
        val first = entries.indexOfFirst { it.top >= threshold }

        if (first < 0) return entries.size

        val entry = entries[first]
        val nested = entry.level >= 1 && isListType(entry.block.type)

        if (nested) {
            val last = min(entries.size, first + 4)

            for (k in first + 1 until last) {
                val candidate = entries[k]
                val isHeading =
                    candidate.block.type == BlockType.SUBHEADING ||
                            candidate.block.type == BlockType.SECTION_HEADER

                if (isHeading && candidate.top - cue.box.top <= metrics.unit * 6) {
                    return k
                }
            }
        }

        return first
    }

    // =========================================================
    // OCR SANITIZATION
    // =========================================================

    /** Generic cleanup only; project-specific fixes go in [ocrCorrections]. */
    private fun sanitizeOcrText(text: String): String {
        var cleaned = text.trim()

        cleaned = cleaned.replace(Regex("[\\u200B\\u00A0]"), " ")
        cleaned = cleaned.replace(Regex("\\s{2,}"), " ")

        for ((regex, replacement) in ocrCorrections) {
            cleaned = cleaned.replace(regex, replacement)
        }

        return cleaned.trim()
    }

    /**
     * Bullets are often read as "·", or as a lowercase "o" for the sub-bullet
     * circle. Before a capital word that is always a bullet; before lowercase
     * text it is only treated as a bullet when the line is indented.
     */
    private fun normalizeBulletGlyph(text: String, indented: Boolean): String {
        val circle = if (indented) Regex("^o\\s+(?=\\S)") else Regex("^o\\s+(?=[A-Z])")

        return text
            .replace(Regex("^[·∙‣⁃]\\s*"), "• ")
            .replace(circle, "◦ ")
    }

    private fun esc(text: String): String = TextUtils.htmlEncode(text)

    // =========================================================
    // SINGLE-LINE CLASSIFICATION
    // =========================================================

    private fun parseSingleLineBlock(
        text: String,
        boundingBox: Rect?,
        level: Int,
        headingLike: Boolean
    ): NoteBlock {

        val trimmed = normalizeBulletGlyph(sanitizeOcrText(text), level >= 1)

        return when {

            isCalloutRule(trimmed) -> {
                val content = trimmed.substringAfter(":").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>NOTE:</b> ${esc(content)}",
                    boundingBox = boundingBox
                )
            }

            isSectionNumberHeader(trimmed) -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<u><b>${esc(trimmed)}</b></u>",
                    boundingBox = boundingBox
                )
            }

            isQuestionHeader(trimmed) -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>${esc(trimmed)}</b>",
                    boundingBox = boundingBox
                )
            }

            isKeyDefinitionRule(trimmed) -> {
                val colonIndex = trimmed.indexOf(":")
                val key = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()

                val symbol = getBulletSymbol(key)
                val cleanKey = if (symbol != null) key.removePrefix(symbol).trim() else key
                val effective = max(level, glyphLevel(symbol))

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.KEY_DEFINITION,
                    formattedText = "${indentSpaces(effective)}${bulletForLevel(effective)} " +
                            "<span style='background-color:#DDF0B4;'><b>${esc(cleanKey)}</b></span>: ${esc(value)}",
                    boundingBox = boundingBox
                )
            }

            isNumberedItemRule(trimmed) -> {
                val match = Regex("^(\\d+)[.)]\\s*(.*)$").find(trimmed)
                val number = match?.groupValues?.getOrNull(1) ?: "1"
                val content = match?.groupValues?.getOrNull(2)?.trim() ?: trimmed

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.NUMBERED_ITEM,
                    formattedText = "${indentSpaces(level)}<b>$number.</b> ${esc(content)}",
                    boundingBox = boundingBox
                )
            }

            isBulletRule(trimmed) -> {
                val symbol = getBulletSymbol(trimmed)
                val cleanText = trimmed.replace(bulletPrefixPattern, "")
                val effective = max(level, glyphLevel(symbol))

                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.BULLET_ITEM,
                    formattedText = "${indentSpaces(effective)}${bulletForLevel(effective)} ${esc(cleanText)}",
                    boundingBox = boundingBox
                )
            }

            isSubheadingRule(trimmed) -> {
                val cleanText = trimmed.removePrefix("###").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SUBHEADING,
                    formattedText = "<b>${esc(cleanText)}</b>",
                    boundingBox = boundingBox
                )
            }

            isSectionHeaderRule(trimmed) -> {
                val cleanHeader = trimmed.removePrefix("##").removePrefix("-").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<u><b>${esc(cleanHeader)}</b></u>",
                    boundingBox = boundingBox
                )
            }

            isMathRule(trimmed) -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.MATHEMATICAL,
                    formattedText = "${indentSpaces(level)}<i>${esc(trimmed)}</i>",
                    boundingBox = boundingBox
                )
            }

            headingLike -> {
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.SUBHEADING,
                    formattedText = "<u><b>${esc(trimmed)}</b></u>",
                    boundingBox = boundingBox
                )
            }

            else -> {
                val cleanContent = trimmed.removePrefix("-").trim()
                NoteBlock(
                    rawText = trimmed,
                    type = BlockType.REGULAR_TEXT,
                    formattedText = esc(cleanContent),
                    boundingBox = boundingBox
                )
            }
        }
    }

    // =========================================================
    // RULES
    // =========================================================

    // Bullet glyph, or a lowercase "o" read instead of a sub-bullet circle.
    private val bulletPrefixPattern =
        Regex("^(?:[\\-*•●○▪◦▸►·∙‣⁃.]\\s*|o\\s+(?=[A-Z]))")

    private val bulletLinePattern =
        Regex("^(?:[\\-*•●○▪◦▸►·∙‣⁃.]\\s*|o\\s+(?=[A-Z])).+")

    private val sectionNumberPattern =
        Regex("^\\d+(?:\\.\\d+)+[.)]?\\s*(?:[-–—:]\\s*)?[A-Z].{2,}")

    private val chapterPattern =
        Regex("^(chapter|section|unit|lesson|topic|part)\\s+([0-9]+|[ivxlc]+)\\b.*", RegexOption.IGNORE_CASE)

    private val standaloneHeaderPattern =
        Regex(
            "^(introduction|overview|summary|conclusion|objectives?|definitions?|examples?|" +
                    "advantages|disadvantages|references|agenda)\\s*:?$",
            RegexOption.IGNORE_CASE
        )

    private val questionPattern =
        Regex(
            "^(what|why|how|which|when|where|who|define|explain|describe|compare|is|are|does|do|can|should)\\b.*",
            RegexOption.IGNORE_CASE
        )

    private val mathRelationPattern =
        Regex("^\\(?[A-Za-z]\\)?\\s*[=≠≈≤≥<>]\\s*\\(?[A-Za-z0-9]")

    private fun isBulletRule(text: String): Boolean = text.matches(bulletLinePattern)

    private fun isNumberedItemRule(text: String): Boolean {
        // "(?!\\d)" keeps section numbers such as "1.1 - Title" and decimals out.
        return text.matches(Regex("^\\d+[.)](?!\\d)\\s*.+"))
    }

    private fun isSectionNumberHeader(text: String): Boolean {
        val t = text.trim()
        return t.length <= 80 && sectionNumberPattern.matches(t)
    }

    private fun isQuestionHeader(text: String): Boolean {
        val t = text.trim()
        return t.endsWith("?") && t.length <= 90 && questionPattern.matches(t)
    }

    private fun isCalloutRule(text: String): Boolean {
        return text.startsWith("NOTE:", ignoreCase = true)
    }

    private fun getBulletSymbol(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("◦") || trimmed.startsWith("○") -> "◦"
            trimmed.startsWith("▸") || trimmed.startsWith("►") -> "▸"
            trimmed.startsWith("▪") -> "▪"
            trimmed.startsWith("•") || trimmed.startsWith("●") ||
                    trimmed.startsWith("·") || trimmed.startsWith("-") || trimmed.startsWith("*") -> "•"
            else -> null
        }
    }

    // A circle / arrow glyph that the OCR really read hints at a deeper level.
    private fun glyphLevel(symbol: String?): Int {
        return when (symbol) {
            "◦" -> 1
            "▸", "▪" -> 2
            else -> 0
        }
    }

    private fun isSectionHeaderRule(text: String): Boolean {
        val trimmed = text.trim()

        if (isBulletRule(trimmed) || isNumberedItemRule(trimmed)) return false
        if (trimmed.startsWith("##")) return true

        if (isSectionNumberHeader(trimmed)) return true
        if (chapterPattern.matches(trimmed)) return true
        if (standaloneHeaderPattern.matches(trimmed)) return true

        if (trimmed.length in 3..45 && trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }) return true
        if (trimmed.endsWith(":") && trimmed.count { it == ':' } == 1 && trimmed.length in 2..45) return true

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
        if (colonIndex !in 2..40) return false

        val beforeColon = text.substring(0, colonIndex).trim()
        val afterColon = text.substring(colonIndex + 1).trim()

        return beforeColon.isNotBlank() &&
                afterColon.isNotBlank() &&
                !beforeColon.startsWith("NOTE", ignoreCase = true)
    }

    private fun isMathRule(text: String): Boolean {
        val containsOperator = text.contains(Regex("[=≠≈±<>≤≥]"))
        val containsNumber = text.any { it.isDigit() }
        return (containsOperator && containsNumber) || mathRelationPattern.containsMatchIn(text)
    }

    /**
     * A short title-like line (no sentence punctuation) that is followed by a
     * list or indented text is a heading of that list: "Scientific Method",
     * "Population (N)", "Census".
     */
    private fun headingLikeText(text: String, nextText: String?, nextIndented: Boolean): Boolean {
        val t = sanitizeOcrText(text)

        if (t.length !in 3..45) return false
        if (t.split(Regex("\\s+")).size > 6) return false
        if (".,;!?:".contains(t.last())) return false
        if (t.contains(":")) return false
        if (!(t.first().isUpperCase() || t.first().isDigit())) return false
        if (isBulletRule(t) || isNumberedItemRule(t)) return false

        val next = nextText?.trim() ?: return false

        return isBulletRule(next) ||
                isNumberedItemRule(next) ||
                isKeyDefinitionRule(next) ||
                nextIndented
    }

    // =========================================================
    // TABLES
    // =========================================================

    private fun buildHtmlTableFromRows(rows: List<TableRow>, metrics: LayoutMetrics): String {
        val allCells = rows.flatMap { it.cells }
        val columnBounds = detectColumnBounds(allCells, metrics)

        if (columnBounds.isEmpty()) {
            return rows.joinToString("<br/>") { row -> row.cells.joinToString(" ") { esc(it.text) } }
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
                val value = esc(cellText.toString().trim()).ifEmpty { "-" }
                val tag = if (isHeader) "th" else "td"
                val style =
                    if (isHeader) "style='background-color:#F2F2F7;padding:8px;font-weight:bold;text-align:left;color:#000000;'"
                    else "style='padding:6px;color:#000000;'"
                builder.append("<$tag $style>$value</$tag>")
            }
            builder.append("</tr>")
        }

        builder.append("</table>")
        return builder.toString()
    }

    private fun detectColumnBounds(cells: List<SpatialCell>, metrics: LayoutMetrics): List<Pair<Int, Int>> {
        val clusterTolerance = metrics.unit * 3.5
        val leftPadding = (metrics.unit * 0.5).toInt()
        val rightPadding = (metrics.unit * 3.0).toInt()

        val clusters = mutableListOf<MutableList<Int>>()

        for (left in cells.map { it.box.left }.sorted()) {
            val cluster = clusters.find { values -> abs(values.average() - left) < clusterTolerance }
            if (cluster != null) cluster += left else clusters += mutableListOf(left)
        }

        return clusters.map { cluster ->
            val minimum = cluster.minOrNull() ?: 0
            val maximum = cluster.maxOrNull() ?: minimum
            Pair(minimum - leftPadding, maximum + rightPadding)
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