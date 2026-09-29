package com.example.note2snap.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

enum class ToolMode {
    NONE,
    PEN,
    HIGHLIGHTER,
    ERASER
}

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class StrokePoint(
        val x: Float,
        val y: Float
    )

    data class VectorStroke(
        val points: List<StrokePoint>,
        val color: Int,
        val width: Float,
        val alpha: Int = 255
    )

    private var currentTool = ToolMode.NONE
    private var penColor: Int = Color.BLACK
    private var highlighterColor: Int = Color.parseColor("#FFFF00")

    private val strokes =
        mutableListOf<VectorStroke>()

    private val undoneStrokes =
        mutableListOf<VectorStroke>()

    private var currentPenPoints =
        mutableListOf<StrokePoint>()

    private var highlighterStartX = 0f
    private var highlighterStartY = 0f
    private var highlighterEndX = 0f
    private var highlighterEndY = 0f

    private var highlighterPreviewPath: Path? = null

    private var highlighterStrokeListener:
            ((Float, Float, Float, Float, Int) -> Unit)? = null

    private var markupChangedListener: (() -> Unit)? = null

    // Lets PdfViewerActivity capture ONE full markup snapshot before a pen
    // or eraser gesture starts, so Undo/Redo can restore both drawings
    // and text highlights together.
    private var beforeMarkupChangeListener: (() -> Unit)? = null

    // Eraser coordinates are also forwarded to PdfViewerActivity so it can
    // remove snapped text-highlight ranges, not only pen strokes.
    private var eraserTouchListener: ((Float, Float) -> Unit)? = null

    fun setTool(tool: ToolMode) {
        currentTool = tool

        if (tool != ToolMode.HIGHLIGHTER) {
            highlighterPreviewPath = null
        }

        invalidate()
    }

    fun setPenColor(color: Int) {
        penColor = color
    }

    fun setHighlighterColor(color: Int) {
        highlighterColor = color
    }

    fun setHighlighterStrokeListener(
        listener:
        ((Float, Float, Float, Float, Int) -> Unit)?
    ) {
        highlighterStrokeListener = listener
    }

    fun setMarkupChangedListener(
        listener: (() -> Unit)?
    ) {
        markupChangedListener = listener
    }

    fun setBeforeMarkupChangeListener(
        listener: (() -> Unit)?
    ) {
        beforeMarkupChangeListener = listener
    }

    fun setEraserTouchListener(
        listener: ((Float, Float) -> Unit)?
    ) {
        eraserTouchListener = listener
    }

    fun getVectorStrokes(): List<VectorStroke> {
        return strokes.map { stroke ->
            VectorStroke(
                points = stroke.points.map { point ->
                    StrokePoint(point.x, point.y)
                },
                color = stroke.color,
                width = stroke.width,
                alpha = stroke.alpha
            )
        }
    }

    fun setVectorStrokes(
        newStrokes: List<VectorStroke>
    ) {
        strokes.clear()
        strokes.addAll(newStrokes)
        undoneStrokes.clear()
        invalidate()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undoneStrokes.add(
                strokes.removeAt(
                    strokes.lastIndex
                )
            )
            markupChangedListener?.invoke()
            invalidate()
        }
    }

    fun redo() {
        if (undoneStrokes.isNotEmpty()) {
            strokes.add(
                undoneStrokes.removeAt(
                    undoneStrokes.lastIndex
                )
            )
            markupChangedListener?.invoke()
            invalidate()
        }
    }

    fun clear() {
        strokes.clear()
        undoneStrokes.clear()
        currentPenPoints.clear()
        highlighterPreviewPath = null
        markupChangedListener?.invoke()
        invalidate()
    }

    fun isEmpty(): Boolean =
        strokes.isEmpty()

    fun hasContent(): Boolean =
        strokes.isNotEmpty()

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        if (currentTool == ToolMode.NONE) {
            return false
        }

        val x = event.x
        val y = event.y

        when (currentTool) {
            ToolMode.PEN -> {
                return handlePenTouch(
                    event,
                    x,
                    y
                )
            }

            ToolMode.HIGHLIGHTER -> {
                return handleHighlighterTouch(
                    event,
                    x,
                    y
                )
            }

            ToolMode.ERASER -> {
                return handleEraserTouch(
                    event,
                    x,
                    y
                )
            }

            ToolMode.NONE -> return false
        }
    }

    private fun handlePenTouch(
        event: MotionEvent,
        x: Float,
        y: Float
    ): Boolean {

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                // Capture the complete markup state before this new stroke.
                beforeMarkupChangeListener?.invoke()
                undoneStrokes.clear()

                currentPenPoints =
                    mutableListOf(
                        normalizedPoint(x, y)
                    )

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val last =
                    currentPenPoints.lastOrNull()

                val current =
                    normalizedPoint(x, y)

                if (
                    last == null ||
                    abs(current.x - last.x) > 0.002f ||
                    abs(current.y - last.y) > 0.002f
                ) {
                    currentPenPoints.add(current)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                currentPenPoints.add(
                    normalizedPoint(x, y)
                )

                if (currentPenPoints.size >= 2) {
                    strokes.add(
                        VectorStroke(
                            points =
                                currentPenPoints.toList(),
                            color = penColor,
                            width = 6f,
                            alpha = 255
                        )
                    )
                }

                currentPenPoints.clear()
                markupChangedListener?.invoke()
                performClick()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                currentPenPoints.clear()
                invalidate()
                return true
            }
        }

        return true
    }

    private fun handleHighlighterTouch(
        event: MotionEvent,
        x: Float,
        y: Float
    ): Boolean {

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                highlighterStartX = x
                highlighterStartY = y
                highlighterEndX = x
                highlighterEndY = y

                highlighterPreviewPath =
                    Path().apply {
                        moveTo(x, y)
                    }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                highlighterEndX = x
                highlighterEndY = y

                highlighterPreviewPath?.lineTo(
                    x,
                    y
                )

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                highlighterEndX = x
                highlighterEndY = y

                highlighterStrokeListener?.invoke(
                    highlighterStartX,
                    highlighterStartY,
                    highlighterEndX,
                    highlighterEndY,
                    highlighterColor
                )

                highlighterPreviewPath = null
                performClick()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                highlighterPreviewPath = null
                invalidate()
                return true
            }
        }

        return true
    }

    private fun handleEraserTouch(
        event: MotionEvent,
        x: Float,
        y: Float
    ): Boolean {

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                if (event.action == MotionEvent.ACTION_DOWN) {
                    // One snapshot per eraser gesture.
                    beforeMarkupChangeListener?.invoke()
                    undoneStrokes.clear()
                }

                // Give the Activity the same eraser position so snapped
                // text highlights can be removed too.
                eraserTouchListener?.invoke(x, y)

                val nx =
                    if (width > 0) x / width else 0f

                val ny =
                    if (height > 0) y / height else 0f

                val threshold =
                    28f / width.coerceAtLeast(1)

                val iterator =
                    strokes.listIterator(
                        strokes.size
                    )

                while (iterator.hasPrevious()) {
                    val stroke =
                        iterator.previous()

                    val hit =
                        stroke.points.any { point ->
                            hypot(
                                point.x - nx,
                                point.y - ny
                            ) <= threshold
                        }

                    if (hit) {
                        undoneStrokes.add(stroke)
                        iterator.remove()
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                markupChangedListener?.invoke()
                performClick()
                return true
            }
        }

        return true
    }

    private fun normalizedPoint(
        x: Float,
        y: Float
    ): StrokePoint {

        return StrokePoint(
            x =
                if (width > 0) {
                    (x / width)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                },
            y =
                if (height > 0) {
                    (y / height)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
        )
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        for (stroke in strokes) {
            drawVectorStroke(
                canvas,
                stroke
            )
        }

        if (currentPenPoints.size >= 2) {
            drawVectorStroke(
                canvas,
                VectorStroke(
                    points =
                        currentPenPoints,
                    color = penColor,
                    width = 6f,
                    alpha = 255
                )
            )
        }

        highlighterPreviewPath?.let { path ->
            val previewPaint =
                Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    color = highlighterColor
                    alpha = 90
                    strokeWidth = 26f
                }

            canvas.drawPath(
                path,
                previewPaint
            )
        }
    }

    private fun drawVectorStroke(
        canvas: Canvas,
        stroke: VectorStroke
    ) {
        if (stroke.points.size < 2) {
            return
        }

        val paint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = stroke.color
                alpha = stroke.alpha
                strokeWidth = stroke.width
            }

        val path = Path()

        val first =
            stroke.points.first()

        path.moveTo(
            first.x * width,
            first.y * height
        )

        for (point in stroke.points.drop(1)) {
            path.lineTo(
                point.x * width,
                point.y * height
            )
        }

        canvas.drawPath(
            path,
            paint
        )
    }
}
