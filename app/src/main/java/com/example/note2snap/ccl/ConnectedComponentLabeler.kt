package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ConnectedComponentLabeler {

    private data class Component(
        val id: Int,
        val box: Rect,
        val pixels: Int
    )

    fun label(
        binary: Bitmap,
        recognitionBitmap: Bitmap
    ): List<Region> {

        val imageWidth = binary.width
        val imageHeight = binary.height

        val pixels = IntArray(imageWidth * imageHeight)

        binary.getPixels(
            pixels,
            0,
            imageWidth,
            0,
            0,
            imageWidth,
            imageHeight
        )

        val foreground =
            BooleanArray(pixels.size) {
                Color.red(pixels[it]) > 127
            }

        val visited =
            BooleanArray(pixels.size)

        val queue =
            ArrayDeque<Int>()

        val components =
            mutableListOf<Component>()

        var nextComponentId = 0

        // =====================================================
        // 1. CONNECTED COMPONENT LABELING
        // =====================================================

        for (start in foreground.indices) {

            if (!foreground[start] || visited[start]) {
                continue
            }

            visited[start] = true
            queue.add(start)

            var left = start % imageWidth
            var right = left

            var top = start / imageWidth
            var bottom = top

            var pixelCount = 0

            while (queue.isNotEmpty()) {

                val index = queue.removeFirst()

                val x = index % imageWidth
                val y = index / imageWidth

                pixelCount++

                left = min(left, x)
                right = max(right, x)

                top = min(top, y)
                bottom = max(bottom, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {

                        if (dx == 0 && dy == 0) {
                            continue
                        }

                        val nx = x + dx
                        val ny = y + dy

                        if (
                            nx !in 0 until imageWidth ||
                            ny !in 0 until imageHeight
                        ) {
                            continue
                        }

                        val next =
                            ny * imageWidth + nx

                        if (
                            foreground[next] &&
                            !visited[next]
                        ) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }
            }

            val box =
                Rect(
                    left,
                    top,
                    right + 1,
                    bottom + 1
                )

            if (
                shouldDiscardRawComponent(
                    box = box,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
            ) {
                continue
            }

            components +=
                Component(
                    id = nextComponentId++,
                    box = box,
                    pixels = pixelCount
                )
        }

        if (components.isEmpty()) {
            return emptyList()
        }

        // =====================================================
        // 2. ESTIMATE NORMAL HANDWRITING HEIGHT
        // =====================================================

        val medianTextHeight =
            estimateMedianTextHeight(
                components = components,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

        // =====================================================
        // 3. TEXT CANDIDATES
        // =====================================================

        val textCandidates =
            components.filter { component ->

                looksLikeTextComponent(
                    component = component,
                    medianTextHeight = medianTextHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
            }

        // =====================================================
        // 4. GROUP TEXT INTO LINES
        // =====================================================

        val lines =
            mutableListOf<MutableList<Component>>()

        val sortedTextCandidates =
            textCandidates.sortedWith(
                compareBy(
                    { it.box.top },
                    { it.box.left }
                )
            )

        sortedTextCandidates.forEach { component ->

            val componentCenterY =
                component.box.centerY()

            val bestLine =
                lines
                    .filter { line ->

                        canJoinTextLine(
                            line = line,
                            component = component,
                            medianTextHeight = medianTextHeight
                        )
                    }
                    .minByOrNull { line ->

                        val lineTop =
                            line.minOf {
                                it.box.top
                            }

                        val lineBottom =
                            line.maxOf {
                                it.box.bottom
                            }

                        val lineCenterY =
                            (lineTop + lineBottom) / 2

                        abs(
                            componentCenterY -
                                    lineCenterY
                        )
                    }

            if (bestLine == null) {
                lines += mutableListOf(component)
            } else {
                bestLine += component
            }
        }

        // =====================================================
        // 5. CREATE TEXT REGIONS
        // =====================================================

        val usedTextComponentIds =
            mutableSetOf<Int>()

        val rawTextRegions =
            lines.mapIndexedNotNull { index, line ->

                if (line.isEmpty()) {
                    return@mapIndexedNotNull null
                }

                val box =
                    createGroupBox(
                        components = line,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        paddingX = PADDING_X,
                        paddingY = PADDING_Y
                    )

                if (
                    shouldDiscardTextRegion(
                        box = box,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        medianTextHeight = medianTextHeight
                    )
                ) {
                    return@mapIndexedNotNull null
                }

                line.forEach {
                    usedTextComponentIds += it.id
                }

                makeRegion(
                    id = 100_000 + index,
                    box = box,
                    area = line.sumOf { it.pixels },
                    type = RegionType.TEXT,
                    source = recognitionBitmap
                )
            }

        // =====================================================
        // 6. UNUSED COMPONENTS = POSSIBLE VISUALS
        // =====================================================

        val unusedComponents =
            components.filter {
                it.id !in usedTextComponentIds
            }
        println()
        println("========== UNUSED COMPONENTS ==========")
        println("Median text height: $medianTextHeight")

        unusedComponents.forEach { component ->

            val box = component.box

            val width = box.width()
            val height = box.height()
            val area = width * height

            val extent =
                component.pixels.toFloat() /
                        area.coerceAtLeast(1)

            val isVisual =
                looksLikeVisualComponent(
                    component = component,
                    medianTextHeight = medianTextHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )

            println(
                "ID=${component.id} " +
                        "box=${width}x${height} " +
                        "pixels=${component.pixels} " +
                        "extent=${"%.3f".format(extent)} " +
                        "VISUAL=$isVisual"
            )
        }

        println("=======================================")
        println()

        val visualCandidates =
            unusedComponents.filter { component ->

                looksLikeVisualComponent(
                    component = component,
                    medianTextHeight = medianTextHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
            }

        // =====================================================
        // 7. MERGE VISUAL COMPONENTS
        // =====================================================

        val mergedVisualComponents =
            mergeVisualComponents(
                input = visualCandidates,
                medianTextHeight = medianTextHeight
            )

        val initialVisualRegions =
            mergedVisualComponents.mapIndexedNotNull { index, component ->

                val paddedBox =
                    expandBox(
                        box = component.box,
                        amount = max(
                            VISUAL_PADDING,
                            medianTextHeight / 3
                        ),
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )

                if (
                    shouldDiscardVisualRegion(
                        box = paddedBox,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        medianTextHeight = medianTextHeight
                    )
                ) {
                    null
                } else {

                    makeRegion(
                        id = index,
                        box = paddedBox,
                        area = component.pixels,
                        type = RegionType.NON_TEXT,
                        source = recognitionBitmap
                    )
                }
            }

        // =====================================================
        // 8. CLEAN + MERGE TEXT FIRST
        // =====================================================

        /*
         * Diagram completion works better after text fragments
         * have already been merged into their final line/cluster.
         *
         * Example:
         * the D-E-F-G row can be made from several raw text
         * fragments. If we try to absorb before text merging,
         * there may be no single candidate representing the row.
         */
        val cleanedTextRegions =
            rawTextRegions
                .filterNot { text ->
                    initialVisualRegions.any { visual ->
                        shouldSuppressTextInsideVisual(
                            textBox = text.boundingBox,
                            visualBox = visual.boundingBox,
                            medianTextHeight = medianTextHeight
                        )
                    }
                }
                .filterNot { text ->
                    isTinyEdgeTextNoise(
                        region = text,
                        imageWidth = recognitionBitmap.width,
                        imageHeight = recognitionBitmap.height,
                        medianTextHeight = medianTextHeight
                    )
                }

        println()
        println("========== BEFORE TEXT MERGE ==========")

        cleanedTextRegions.forEachIndexed { index, region ->

            val box = region.boundingBox

            println(
                "${index + 1}. " +
                        "ID=${region.id} " +
                        "box=${box.width()}x${box.height()} " +
                        "coords=(${box.left},${box.top})-(${box.right},${box.bottom}) " +
                        "area=${region.pixelArea}"
            )
        }

        println("=======================================")
        println()

        val mergedTextRegions =
            mergeOverlappingTextRegions(
                input = cleanedTextRegions,
                source = recognitionBitmap,
                medianTextHeight = medianTextHeight
            )

        // =====================================================
        // 9. COMPLETE DIAGRAMS USING MERGED TEXT REGIONS
        // =====================================================

        val diagramCompletion =
            completeVisualRegionsWithNearbyShapeText(
                textRegions = mergedTextRegions,
                visualRegions = initialVisualRegions,
                source = recognitionBitmap,
                medianTextHeight = medianTextHeight,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

        val visualRegions =
            diagramCompletion.first

        val absorbedTextRegionIds =
            diagramCompletion.second

        // =====================================================
        // 10. FINAL TEXT AFTER DIAGRAM COMPLETION
        // =====================================================

        val finalTextRegions =
            mergedTextRegions
                .filterNot { text ->
                    text.id in absorbedTextRegionIds
                }
                .filterNot { text ->
                    visualRegions.any { visual ->
                        shouldSuppressTextInsideVisual(
                            textBox = text.boundingBox,
                            visualBox = visual.boundingBox,
                            medianTextHeight = medianTextHeight
                        )
                    }
                }

        // =====================================================
        // 11. FINAL RESULT
        // =====================================================

        val result =
            (finalTextRegions + visualRegions)
                .sortedWith(
                    compareBy(
                        { it.boundingBox.top },
                        { it.boundingBox.left }
                    )
                )

        if (result.size > MAX_REGIONS) {
            throw TooManyRegionsException(
                "This image produced too many regions (${result.size}). Retake it with cleaner lighting."
            )
        }

        return result
    }

    private fun isTinyEdgeTextNoise(
        region: Region,
        imageWidth: Int,
        imageHeight: Int,
        medianTextHeight: Int
    ): Boolean {

        val box =
            region.boundingBox

        val touchesLeft =
            box.left <= 2

        val touchesTop =
            box.top <= 2

        val touchesRight =
            box.right >=
                    imageWidth - 2

        val touchesBottom =
            box.bottom >=
                    imageHeight - 2

        val touchesImageEdge =
            touchesLeft ||
                    touchesTop ||
                    touchesRight ||
                    touchesBottom

        if (!touchesImageEdge) {
            return false
        }

        val width =
            box.width()

        val height =
            box.height()

        /*
         * Thin vertical pieces attached to the
         * left/right borders are usually board
         * frames rather than handwriting.
         */
        val verticalEdgeArtifact =
            (
                    touchesLeft ||
                            touchesRight
                    ) &&
                    width <=
                    max(
                        24,
                        medianTextHeight +
                                medianTextHeight / 2
                    ) &&
                    height >=
                    medianTextHeight

        /*
         * Thin horizontal pieces attached to the
         * top/bottom border are usually frame,
         * tray or crop artifacts.
         */
        val horizontalEdgeArtifact =
            (
                    touchesTop ||
                            touchesBottom
                    ) &&
                    height <=
                    max(
                        18,
                        medianTextHeight
                    ) &&
                    width >=
                    medianTextHeight * 3

        /*
         * Tiny isolated fragment directly on
         * any image border.
         */
        val tinyEdgeFragment =
            width <=
                    medianTextHeight &&
                    height <=
                    medianTextHeight &&
                    region.pixelArea <=
                    medianTextHeight * 8

        return verticalEdgeArtifact ||
                horizontalEdgeArtifact ||
                tinyEdgeFragment
    }

    // =========================================================
    // MEDIAN TEXT HEIGHT
    // =========================================================

    private fun estimateMedianTextHeight(
        components: List<Component>,
        imageWidth: Int,
        imageHeight: Int
    ): Int {

        val possibleCharacterHeights =
            components
                .filter { component ->

                    val width =
                        component.box.width()

                    val height =
                        component.box.height()

                    if (width <= 0 || height <= 0) {
                        return@filter false
                    }

                    val widthRatio =
                        width.toFloat() /
                                imageWidth.coerceAtLeast(1)

                    val heightRatio =
                        height.toFloat() /
                                imageHeight.coerceAtLeast(1)

                    val aspect =
                        max(
                            width.toFloat() /
                                    height.coerceAtLeast(1),

                            height.toFloat() /
                                    width.coerceAtLeast(1)
                        )

                    widthRatio < 0.08f &&
                            heightRatio < 0.08f &&
                            aspect < 5f
                }
                .map {
                    it.box.height()
                }
                .sorted()

        if (possibleCharacterHeights.isEmpty()) {
            return max(
                20,
                (imageHeight * 0.02f).toInt()
            )
        }

        return possibleCharacterHeights[
            possibleCharacterHeights.size / 2
        ].coerceAtLeast(1)
    }

    // =========================================================
    // TEXT COMPONENT
    // =========================================================

    private fun looksLikeTextComponent(
        component: Component,
        medianTextHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {

        val box =
            component.box

        val width =
            box.width()

        val height =
            box.height()

        if (
            width <= 0 ||
            height <= 0
        ) {
            return false
        }

        val area =
            width * height

        val extent =
            component.pixels.toFloat() /
                    area.coerceAtLeast(1)

        val aspect =
            max(
                width.toFloat() /
                        height.coerceAtLeast(1),

                height.toFloat() /
                        width.coerceAtLeast(1)
            )

        val minTextHeight =
            max(
                3,
                (medianTextHeight * 0.30f).toInt()
            )

        /*
         * Titles, section headings and mathematical
         * expressions can be much larger than normal
         * handwriting.
         */
        val maxTextHeight =
            max(
                medianTextHeight + 1,
                (medianTextHeight * 6.0f).toInt()
            )

        if (
            height < minTextHeight ||
            height > maxTextHeight
        ) {
            return false
        }

        /*
         * Very thin long components are usually
         * underlines, arrows, separators or borders.
         */
        if (
            aspect > 9f &&
            height <= medianTextHeight &&
            extent < 0.35f
        ) {
            return false
        }

        /*
         * Wide sparse regions can still be genuine
         * handwritten content:
         *
         * - underlined headings
         * - large titles
         * - equations / fractions
         *
         * Examples:
         * "Project Planning"
         * "Properties:"
         * quadratic formula
         */
        val wideTextLike =
            aspect >= 2.5f &&
                    width <= imageWidth * 0.80f &&
                    height <= medianTextHeight * 6

        /*
         * Tall sparse components with roughly square
         * proportions are much more likely to be
         * diagrams, circles, boxes or tree nodes.
         */
        val largeSparseShape =
            height > medianTextHeight * 3.2f &&
                    extent < 0.12f &&
                    !wideTextLike

        if (largeSparseShape) {
            return false
        }

        /*
         * Extremely tall objects are not normal
         * handwriting even when they passed the
         * relative-height checks above.
         */
        if (
            height > imageHeight * 0.18f
        ) {
            return false
        }

        return true
    }

    // =========================================================
    // TEXT LINE GROUPING
    // =========================================================

    private fun canJoinTextLine(
        line: List<Component>,
        component: Component,
        medianTextHeight: Int
    ): Boolean {

        val lineTop =
            line.minOf { it.box.top }

        val lineBottom =
            line.maxOf { it.box.bottom }

        val lineLeft =
            line.minOf { it.box.left }

        val lineRight =
            line.maxOf { it.box.right }

        val componentTop =
            component.box.top

        val componentBottom =
            component.box.bottom

        val componentCenterY =
            component.box.centerY()

        val lineCenterY =
            (lineTop + lineBottom) / 2

        val overlapTop =
            max(
                lineTop,
                componentTop
            )

        val overlapBottom =
            min(
                lineBottom,
                componentBottom
            )

        val verticalOverlap =
            max(
                0,
                overlapBottom - overlapTop
            )

        val lineHeight =
            max(
                1,
                lineBottom - lineTop
            )

        val componentHeight =
            max(
                1,
                component.box.height()
            )

        val smallerHeight =
            min(
                lineHeight,
                componentHeight
            )

        val overlapRatio =
            verticalOverlap.toFloat() /
                    smallerHeight.toFloat()

        val centerDistance =
            abs(
                componentCenterY -
                        lineCenterY
            )

        val verticallyCompatible =
            overlapRatio >= 0.30f ||
                    centerDistance <=
                    medianTextHeight * 1.50f

        if (!verticallyCompatible) {
            return false
        }

        val horizontalGap =
            when {

                component.box.left > lineRight ->
                    component.box.left - lineRight

                component.box.right < lineLeft ->
                    lineLeft - component.box.right

                else ->
                    0
            }

        val maximumHorizontalGap =
            max(
                30,
                medianTextHeight * 9
            )

        return horizontalGap <=
                maximumHorizontalGap
    }

    private fun mergeOverlappingTextRegions(
        input: List<Region>
    ): List<Region> {

        if (input.isEmpty()) return emptyList()

        val remaining = input.toMutableList()
        val merged = mutableListOf<Region>()

        while (remaining.isNotEmpty()) {

            var current = remaining.removeAt(0)

            var changed = true

            while (changed) {

                changed = false

                val iterator = remaining.iterator()

                while (iterator.hasNext()) {

                    val other = iterator.next()

                    val a = current.boundingBox
                    val b = other.boundingBox

                    val verticalOverlap =
                        min(a.bottom, b.bottom) -
                                max(a.top, b.top)

                    val smallerHeight =
                        min(
                            a.height(),
                            b.height()
                        ).coerceAtLeast(1)

                    val verticalOverlapRatio =
                        verticalOverlap.toFloat() /
                                smallerHeight.toFloat()

                    val horizontalGap =
                        when {
                            b.left > a.right ->
                                b.left - a.right

                            a.left > b.right ->
                                a.left - b.right

                            else ->
                                0
                        }

                    val shouldMerge =
                        verticalOverlapRatio >= 0.45f &&
                                horizontalGap <= 40

                    if (shouldMerge) {

                        val mergedBox =
                            Rect(
                                min(a.left, b.left),
                                min(a.top, b.top),
                                max(a.right, b.right),
                                max(a.bottom, b.bottom)
                            )

                        current =
                            Region(
                                id = current.id,
                                boundingBox = mergedBox,
                                type = RegionType.TEXT,
                                pixelArea =
                                    current.pixelArea +
                                            other.pixelArea,
                                croppedBitmap =
                                    current.croppedBitmap
                            )

                        iterator.remove()
                        changed = true
                    }
                }
            }

            merged += current
        }

        return merged
    }

    // =========================================================
    // VISUAL COMPONENT
    // =========================================================

    private fun looksLikeVisualComponent(
        component: Component,
        medianTextHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {

        val box = component.box

        val width = box.width()
        val height = box.height()

        if (width <= 0 || height <= 0) {
            return false
        }

        // Ignore very thin horizontal strokes.
// Usually underlines, separators, or board artifacts.
        if (
            height <= max(5, medianTextHeight / 3) &&
            width >= medianTextHeight * 4
        ) {
            return false
        }

// Ignore very thin vertical strokes.
// Usually board/frame artifacts.
        if (
            width <= max(5, medianTextHeight / 3) &&
            height >= medianTextHeight * 4
        ) {
            return false
        }

        val area =
            width * height

        val extent =
            component.pixels.toFloat() /
                    area.coerceAtLeast(1)

        val maxDimension =
            max(width, height)

        val minDimension =
            min(width, height)

        val aspect =
            maxDimension.toFloat() /
                    minDimension.coerceAtLeast(1)

        // This function receives UNUSED components only,
        // so we can be relatively permissive.

        // Circle / box / shape-like component.
        if (
            width >= medianTextHeight &&
            height >= medianTextHeight &&
            extent < 0.55f
        ) {
            return true
        }

        // Arrow / connector / long stroke.
        if (
            maxDimension >= medianTextHeight * 2 &&
            aspect >= 1.8f
        ) {
            return true
        }

        // Moderately large sparse object.
        if (
            width > imageWidth * 0.025f &&
            height > imageHeight * 0.025f &&
            extent < 0.50f
        ) {
            return true
        }

        return false
    }

    // =========================================================
    // MERGE VISUAL COMPONENTS
    // =========================================================

    private fun mergeVisualComponents(
        input: List<Component>,
        medianTextHeight: Int
    ): List<Component> {

        if (input.isEmpty()) {
            return emptyList()
        }

        val remaining =
            input.toMutableList()

        val merged =
            mutableListOf<Component>()

        /*
         * Visual parts should be genuinely close.
         *
         * The previous value of medianTextHeight * 6
         * allowed unrelated shapes, text decorations,
         * board edges, and distant objects to chain
         * together into one huge visual region.
         */
        val allowedGap =
            max(
                18,
                medianTextHeight * 2
            )

        while (remaining.isNotEmpty()) {

            var current =
                remaining.removeAt(0)

            var changed = true

            while (changed) {

                changed = false

                val iterator =
                    remaining.iterator()

                while (iterator.hasNext()) {

                    val other =
                        iterator.next()

                    val horizontalGap =
                        calculateHorizontalGap(
                            current.box,
                            other.box
                        )

                    val verticalGap =
                        calculateVerticalGap(
                            current.box,
                            other.box
                        )

                    /*
                     * Require proximity in BOTH directions.
                     */
                    val closeEnough =
                        horizontalGap <= allowedGap &&
                                verticalGap <= allowedGap

                    if (!closeEnough) {
                        continue
                    }

                    val mergedBox =
                        Rect(
                            min(
                                current.box.left,
                                other.box.left
                            ),
                            min(
                                current.box.top,
                                other.box.top
                            ),
                            max(
                                current.box.right,
                                other.box.right
                            ),
                            max(
                                current.box.bottom,
                                other.box.bottom
                            )
                        )

                    /*
                     * Prevent runaway / chain merging.
                     *
                     * A single visual region should not
                     * suddenly grow to occupy most of the
                     * entire whiteboard.
                     */
                    val currentWidth =
                        current.box.width()
                            .coerceAtLeast(1)

                    val currentHeight =
                        current.box.height()
                            .coerceAtLeast(1)

                    val otherWidth =
                        other.box.width()
                            .coerceAtLeast(1)

                    val otherHeight =
                        other.box.height()
                            .coerceAtLeast(1)

                    val referenceWidth =
                        max(
                            currentWidth,
                            otherWidth
                        )

                    val referenceHeight =
                        max(
                            currentHeight,
                            otherHeight
                        )

                    val excessiveGrowth =
                        mergedBox.width() >
                                referenceWidth * 2.5f ||
                                mergedBox.height() >
                                referenceHeight * 2.5f

                    if (excessiveGrowth) {
                        continue
                    }

                    current =
                        Component(
                            id = current.id,
                            box = mergedBox,
                            pixels =
                                current.pixels +
                                        other.pixels
                        )

                    iterator.remove()

                    changed = true
                }
            }

            merged += current
        }

        return merged
    }

    private fun calculateHorizontalGap(
        first: Rect,
        second: Rect
    ): Int {

        return when {

            second.left > first.right ->
                second.left - first.right

            first.left > second.right ->
                first.left - second.right

            else ->
                0
        }
    }

    private fun calculateVerticalGap(
        first: Rect,
        second: Rect
    ): Int {

        return when {

            second.top > first.bottom ->
                second.top - first.bottom

            first.top > second.bottom ->
                first.top - second.bottom

            else ->
                0
        }
    }

    // =========================================================
    // =========================================================
    // RAW FILTER
    // =========================================================

    private fun shouldDiscardRawComponent(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {

        val width = box.width()
        val height = box.height()

        if (width <= 0 || height <= 0) {
            return true
        }

        val area = width * height

        if (area < MIN_COMPONENT_AREA) {
            return true
        }

        val widthRatio =
            width.toFloat() /
                    imageWidth.coerceAtLeast(1)

        val heightRatio =
            height.toFloat() /
                    imageHeight.coerceAtLeast(1)

        val touchesLeft =
            box.left <= 2

        val touchesRight =
            box.right >= imageWidth - 2

        val touchesTop =
            box.top <= 2

        val touchesBottom =
            box.bottom >= imageHeight - 2

        // Huge component spanning almost the entire width.
        if (
            widthRatio > 0.90f &&
            heightRatio > 0.15f
        ) {
            return true
        }

        // Huge component spanning almost the entire height.
        if (
            heightRatio > 0.90f &&
            widthRatio > 0.15f
        ) {
            return true
        }

        // Component attached to both left and right edges.
        if (
            touchesLeft &&
            touchesRight &&
            widthRatio > 0.80f
        ) {
            return true
        }

        // Component attached to both top and bottom edges.
        if (
            touchesTop &&
            touchesBottom &&
            heightRatio > 0.80f
        ) {
            return true
        }

        // Long horizontal board/frame line.
        if (
            widthRatio > 0.80f &&
            heightRatio < 0.04f
        ) {
            return true
        }

        // Tall thin board/frame line.
        if (
            heightRatio > 0.20f &&
            widthRatio < 0.015f
        ) {
            return true
        }

        return false
    }

// TEXT REGION FILTER
// =========================================================

    private fun shouldDiscardTextRegion(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
        medianTextHeight: Int
    ): Boolean {

        val width = box.width()
        val height = box.height()

        if (width <= 0 || height <= 0) {
            return true
        }

        // Remove regions that are too short to represent useful text.
        if (
            height <
            max(
                8,
                medianTextHeight / 2
            )
        ) {
            return true
        }

        // Remove nearly full-width thin board/frame artifacts.
        if (
            width > imageWidth * 0.90f &&
            height < imageHeight * 0.06f
        ) {
            return true
        }

        val widthRatio =
            width.toFloat() /
                    imageWidth.coerceAtLeast(1)

        val heightRatio =
            height.toFloat() /
                    imageHeight.coerceAtLeast(1)

        /*
         * Reject wide, shallow text-like regions that sit on the
         * physical top/bottom edge of the photo. These are usually
         * marker trays, metal borders, or crop artifacts rather
         * than note content.
         */
        val nearBottom =
            box.bottom >=
                    (imageHeight * 0.94f).toInt()

        if (
            nearBottom &&
            widthRatio >= 0.25f &&
            heightRatio <= 0.12f
        ) {
            return true
        }

        val nearTop =
            box.top <=
                    (imageHeight * 0.035f).toInt()

        if (
            nearTop &&
            widthRatio >= 0.25f &&
            heightRatio <= 0.10f
        ) {
            return true
        }

        return false
    }

    // =========================================================
    // =========================================================
    // VISUAL REGION FILTER
    // =========================================================

    private fun shouldDiscardVisualRegion(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
        medianTextHeight: Int
    ): Boolean {

        val width =
            box.width()

        val height =
            box.height()

        if (
            width <= 0 ||
            height <= 0
        ) {
            return true
        }

        // Real diagrams need meaningful size in both dimensions.
        if (
            width < medianTextHeight * 2 ||
            height < medianTextHeight * 2
        ) {
            return true
        }

        val widthRatio =
            width.toFloat() /
                    imageWidth.coerceAtLeast(1)

        val heightRatio =
            height.toFloat() /
                    imageHeight.coerceAtLeast(1)

        // Reject nearly full-width shallow frame/tray regions.
        if (
            widthRatio >= 0.70f &&
            heightRatio <= 0.12f
        ) {
            return true
        }

        // Reject nearly full-height narrow side-frame regions.
        if (
            heightRatio >= 0.70f &&
            widthRatio <= 0.08f
        ) {
            return true
        }

        // Reject enormous background/runaway merged regions.
        if (
            widthRatio > 0.85f &&
            heightRatio > 0.60f
        ) {
            return true
        }

        /*
 * Reject objects very close to the physical
 * bottom of the photo when they are wide and shallow.
 *
 * These are normally:
 * - marker trays
 * - metal whiteboard borders
 * - crop/frame artifacts
 */
        val nearBottom =
            box.bottom >=
                    (imageHeight * 0.94f).toInt()

        val bottomFrame =
            nearBottom &&
                    widthRatio >= 0.25f &&
                    heightRatio <= 0.12f

        if (bottomFrame) {
            return true
        }

        /*
         * Same idea for the very top of the photograph.
         */
        val nearTop =
            box.top <=
                    (imageHeight * 0.035f).toInt()

        val topFrame =
            nearTop &&
                    widthRatio >= 0.25f &&
                    heightRatio <= 0.10f

        if (topFrame) {
            return true
        }
        return false
    }

    // =========================================================
    // DIAGRAM COMPLETION
    // =========================================================

    private fun completeVisualRegionsWithNearbyShapeText(
        textRegions: List<Region>,
        visualRegions: List<Region>,
        source: Bitmap,
        medianTextHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<List<Region>, Set<Int>> {

        if (
            textRegions.isEmpty() ||
            visualRegions.isEmpty()
        ) {
            return Pair(
                visualRegions,
                emptySet()
            )
        }

        val absorbedTextRegionIds =
            mutableSetOf<Int>()

        val completedVisualRegions =
            visualRegions.map { visual ->

                /*
                 * IMPORTANT:
                 * Absorb at most ONE nearby text region.
                 *
                 * The previous version repeatedly absorbed
                 * candidates. That could create chain growth:
                 *
                 * visual -> nearby text -> more nearby text ->
                 * giant visual region.
                 *
                 * One completion step is enough for cases such
                 * as a missing bottom row of diagram nodes.
                 */
                val candidate =
                    textRegions
                        .asSequence()
                        .filter {
                            it.id !in absorbedTextRegionIds
                        }
                        .filter {
                            shouldAbsorbTextIntoVisual(
                                text = it,
                                visual = visual,
                                medianTextHeight = medianTextHeight,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight
                            )
                        }
                        .minByOrNull {
                            distanceBetweenBoxes(
                                first = it.boundingBox,
                                second = visual.boundingBox
                            )
                        }

                if (candidate == null) {
                    visual
                } else {

                    val visualBox =
                        visual.boundingBox

                    val textBox =
                        candidate.boundingBox

                    val mergedBox =
                        Rect(
                            min(
                                visualBox.left,
                                textBox.left
                            ),
                            min(
                                visualBox.top,
                                textBox.top
                            ),
                            max(
                                visualBox.right,
                                textBox.right
                            ),
                            max(
                                visualBox.bottom,
                                textBox.bottom
                            )
                        )

                    /*
                     * Safety check after expansion.
                     *
                     * Never let diagram completion turn a valid
                     * visual into a board-frame / giant-region
                     * artifact.
                     */
                    if (
                        shouldDiscardVisualRegion(
                            box = mergedBox,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            medianTextHeight = medianTextHeight
                        )
                    ) {
                        visual
                    } else {

                        absorbedTextRegionIds +=
                            candidate.id

                        makeRegion(
                            id = visual.id,
                            box = mergedBox,
                            area =
                                visual.pixelArea +
                                        candidate.pixelArea,
                            type = RegionType.NON_TEXT,
                            source = source
                        )
                    }
                }
            }

        return Pair(
            completedVisualRegions,
            absorbedTextRegionIds
        )
    }

    private fun shouldAbsorbTextIntoVisual(
        text: Region,
        visual: Region,
        medianTextHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {

        val textBox =
            text.boundingBox

        val visualBox =
            visual.boundingBox

        val textWidth =
            textBox.width()
                .coerceAtLeast(1)

        val textHeight =
            textBox.height()
                .coerceAtLeast(1)

        val visualWidth =
            visualBox.width()
                .coerceAtLeast(1)

        val visualHeight =
            visualBox.height()
                .coerceAtLeast(1)

        /*
         * Only a substantial but still reasonably compact
         * visual can act as a diagram seed.
         *
         * This blocks already-huge layouts/tables from
         * swallowing nearby text.
         */
        val substantialVisual =
            visualWidth >= medianTextHeight * 6 &&
                    visualHeight >= medianTextHeight * 6

        if (!substantialVisual) {
            return false
        }

        val visualWidthRatio =
            visualWidth.toFloat() /
                    imageWidth.coerceAtLeast(1)

        val visualHeightRatio =
            visualHeight.toFloat() /
                    imageHeight.coerceAtLeast(1)

        if (
            visualWidthRatio > 0.55f ||
            visualHeightRatio > 0.55f
        ) {
            return false
        }

        /*
         * Diagram completion is for a continuation beneath
         * or inside the lower part of an existing diagram.
         *
         * Do not absorb headings, notes above the diagram,
         * or unrelated side text.
         */
        if (
            textBox.centerY() <
            visualBox.centerY()
        ) {
            return false
        }

        val minimumCandidateTop =
            visualBox.top +
                    (visualHeight * 0.40f).toInt()

        if (
            textBox.top <
            minimumCandidateTop
        ) {
            return false
        }

        /*
         * Diagram node rows are usually taller than normal
         * handwriting because circles/boxes and labels share
         * the same region.
         */
        val unusuallyTallText =
            textHeight >=
                    (medianTextHeight * 2.2f).toInt()

        if (!unusuallyTallText) {
            return false
        }

        /*
         * Reject edge/frame/tray text candidates before they
         * can enlarge a visual.
         */
        val textWidthRatio =
            textWidth.toFloat() /
                    imageWidth.coerceAtLeast(1)

        val textHeightRatio =
            textHeight.toFloat() /
                    imageHeight.coerceAtLeast(1)

        val nearBottom =
            textBox.bottom >=
                    (imageHeight * 0.94f).toInt()

        val bottomFrameLike =
            nearBottom &&
                    textWidthRatio >= 0.25f &&
                    textHeightRatio <= 0.12f

        if (bottomFrameLike) {
            return false
        }

        val nearTop =
            textBox.top <=
                    (imageHeight * 0.035f).toInt()

        val topFrameLike =
            nearTop &&
                    textWidthRatio >= 0.25f &&
                    textHeightRatio <= 0.10f

        if (topFrameLike) {
            return false
        }

        /*
         * A completion row may be slightly wider than the
         * current visual, but it should not be dramatically
         * wider.
         */
        if (
            textWidth >
            (visualWidth * 1.40f).toInt()
        ) {
            return false
        }

        /*
         * Shape-like text regions are sparse inside their
         * bounding rectangle.
         */
        val textExtent =
            text.pixelArea.toFloat() /
                    (textWidth * textHeight)
                        .coerceAtLeast(1)
                        .toFloat()

        if (textExtent > 0.24f) {
            return false
        }

        val horizontalOverlap =
            max(
                0,
                min(
                    textBox.right,
                    visualBox.right
                ) -
                        max(
                            textBox.left,
                            visualBox.left
                        )
            )

        val horizontalOverlapRatio =
            horizontalOverlap.toFloat() /
                    textWidth.toFloat()

        if (horizontalOverlapRatio < 0.60f) {
            return false
        }

        val verticalGap =
            calculateVerticalGap(
                textBox,
                visualBox
            )

        if (
            verticalGap >
            medianTextHeight * 2
        ) {
            return false
        }

        val leftOverflow =
            max(
                0,
                visualBox.left -
                        textBox.left
            )

        val rightOverflow =
            max(
                0,
                textBox.right -
                        visualBox.right
            )

        val maximumOverflow =
            medianTextHeight * 5

        if (
            leftOverflow > maximumOverflow ||
            rightOverflow > maximumOverflow
        ) {
            return false
        }

        return true
    }

    private fun distanceBetweenBoxes(
        first: Rect,
        second: Rect
    ): Int {

        val horizontalGap =
            calculateHorizontalGap(
                first,
                second
            )

        val verticalGap =
            calculateVerticalGap(
                first,
                second
            )

        return horizontalGap +
                verticalGap
    }

    // SUPPRESS TEXT INSIDE VISUAL
    // =========================================================

    private fun shouldSuppressTextInsideVisual(
        textBox: Rect,
        visualBox: Rect,
        medianTextHeight: Int
    ): Boolean {

        val intersection =
            Rect()

        if (
            !intersection.setIntersect(
                textBox,
                visualBox
            )
        ) {
            return false
        }

        val textArea =
            textBox.width() *
                    textBox.height()

        if (textArea <= 0) {
            return false
        }

        val intersectionArea =
            intersection.width() *
                    intersection.height()

        val overlap =
            intersectionArea.toFloat() /
                    textArea.toFloat()

        val shortTextRegion =
            textBox.width() <=
                    medianTextHeight * 30

        return (
                overlap >= 0.60f &&
                        shortTextRegion
                )
    }

    // =========================================================
    // BOX HELPERS
    // =========================================================

    private fun createGroupBox(
        components: List<Component>,
        imageWidth: Int,
        imageHeight: Int,
        paddingX: Int,
        paddingY: Int
    ): Rect {

        return Rect(
            (
                    components.minOf {
                        it.box.left
                    } - paddingX
                    ).coerceAtLeast(0),

            (
                    components.minOf {
                        it.box.top
                    } - paddingY
                    ).coerceAtLeast(0),

            (
                    components.maxOf {
                        it.box.right
                    } + paddingX
                    ).coerceAtMost(imageWidth),

            (
                    components.maxOf {
                        it.box.bottom
                    } + paddingY
                    ).coerceAtMost(imageHeight)
        )
    }

    private fun expandBox(
        box: Rect,
        amount: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {

        return Rect(
            (box.left - amount)
                .coerceAtLeast(0),

            (box.top - amount)
                .coerceAtLeast(0),

            (box.right + amount)
                .coerceAtMost(imageWidth),

            (box.bottom + amount)
                .coerceAtMost(imageHeight)
        )
    }

    // =========================================================
    // REGION CREATION
    // =========================================================

    private fun mergeOverlappingTextRegions(
        input: List<Region>,
        source: Bitmap,
        medianTextHeight: Int
    ): List<Region> {

        if (input.isEmpty()) {
            return emptyList()
        }

        val remaining =
            input.toMutableList()

        val result =
            mutableListOf<Region>()

        while (remaining.isNotEmpty()) {

            var current =
                remaining.removeAt(0)

            var changed = true

            while (changed) {

                changed = false

                val iterator =
                    remaining.iterator()

                while (iterator.hasNext()) {

                    val other =
                        iterator.next()

                    val first =
                        current.boundingBox

                    val second =
                        other.boundingBox

                    val overlapTop =
                        max(
                            first.top,
                            second.top
                        )

                    val overlapBottom =
                        min(
                            first.bottom,
                            second.bottom
                        )

                    val verticalOverlap =
                        max(
                            0,
                            overlapBottom - overlapTop
                        )

                    val smallerHeight =
                        min(
                            first.height(),
                            second.height()
                        ).coerceAtLeast(1)

                    val verticalOverlapRatio =
                        verticalOverlap.toFloat() /
                                smallerHeight.toFloat()

                    val horizontalGap =
                        when {

                            second.left > first.right ->
                                second.left - first.right

                            first.left > second.right ->
                                first.left - second.right

                            else ->
                                0
                        }


                    val firstCenterY =
                        first.centerY()

                    val secondCenterY =
                        second.centerY()

                    val centerDistance =
                        abs(
                            firstCenterY -
                                    secondCenterY
                        )

                    val smallerRegionHeight =
                        min(
                            first.height(),
                            second.height()
                        )

                    val closeCenters =
                        centerDistance <=
                                medianTextHeight * 1.50f

                    val smallFragment =
                        smallerRegionHeight <=
                                medianTextHeight * 1.50f

                    val sameLine =
                        verticalOverlapRatio >= 0.60f &&
                                horizontalGap <=
                                medianTextHeight * 3 &&
                                (
                                        closeCenters ||
                                                smallFragment
                                        )
                    if (sameLine) {

                        val mergedBox =
                            Rect(
                                min(
                                    first.left,
                                    second.left
                                ),
                                min(
                                    first.top,
                                    second.top
                                ),
                                max(
                                    first.right,
                                    second.right
                                ),
                                max(
                                    first.bottom,
                                    second.bottom
                                )
                            )

                        current =
                            makeRegion(
                                id = current.id,
                                box = mergedBox,
                                area =
                                    current.pixelArea +
                                            other.pixelArea,
                                type = RegionType.TEXT,
                                source = source
                            )

                        iterator.remove()

                        changed = true
                    }
                }
            }

            result += current
        }

        return result
    }

    private fun makeRegion(
        id: Int,
        box: Rect,
        area: Int,
        type: RegionType,
        source: Bitmap
    ): Region {

        val safe =
            Rect(
                box.left.coerceIn(
                    0,
                    source.width - 1
                ),

                box.top.coerceIn(
                    0,
                    source.height - 1
                ),

                box.right.coerceIn(
                    1,
                    source.width
                ),

                box.bottom.coerceIn(
                    1,
                    source.height
                )
            )

        return Region(
            id = id,
            boundingBox = safe,
            type = type,
            pixelArea = area,
            croppedBitmap =
                Bitmap.createBitmap(
                    source,
                    safe.left,
                    safe.top,
                    safe.width(),
                    safe.height()
                )
        )
    }

    private companion object {

        const val PADDING_X = 10
        const val PADDING_Y = 6
        const val VISUAL_PADDING = 12

        const val MIN_COMPONENT_AREA = 12
        const val MAX_REGIONS = 300
    }
}

class TooManyRegionsException(
    message: String
) : Exception(message)


