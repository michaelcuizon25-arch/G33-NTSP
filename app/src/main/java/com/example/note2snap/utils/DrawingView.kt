package com.example.note2snap.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

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

    private var currentTool = ToolMode.NONE
    private var penColor: Int = Color.BLACK
    private var highlighterColor: Int = Color.parseColor("#FFFF00")

    private val paths = mutableListOf<DrawnPath>()
    private val undonePaths = mutableListOf<DrawnPath>()

    private var currentPath: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    data class DrawnPath(
        val path: Path,
        val paint: Paint
    )

    init {
        // Software rendering layer is required for PorterDuff clearing mode
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setTool(tool: ToolMode) {
        currentTool = tool
        invalidate()
    }

    fun setPenColor(color: Int) {
        penColor = color
    }

    fun setHighlighterColor(color: Int) {
        highlighterColor = color
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeAt(paths.size - 1))
            invalidate()
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeAt(undonePaths.size - 1))
            invalidate()
        }
    }

    fun clear() {
        paths.clear()
        undonePaths.clear()
        currentPath = null
        invalidate()
    }

    fun isEmpty(): Boolean = paths.isEmpty()

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentTool == ToolMode.NONE) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                undonePaths.clear()

                val newPath = Path().apply { moveTo(x, y) }
                val newPaint = createPaintForTool(currentTool)

                currentPath = newPath
                lastX = x
                lastY = y

                paths.add(DrawnPath(newPath, newPaint))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                if (dx >= 4f || dy >= 4f) {
                    currentPath?.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                currentPath?.lineTo(x, y)
                currentPath = null
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                currentPath = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun createPaintForTool(tool: ToolMode): Paint {
        return Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND

            when (tool) {
                ToolMode.PEN -> {
                    color = penColor
                    strokeWidth = 6f
                }
                ToolMode.HIGHLIGHTER -> {
                    color = highlighterColor
                    alpha = 100
                    strokeWidth = 32f
                }
                ToolMode.ERASER -> {
                    color = Color.TRANSPARENT
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    strokeWidth = 44f
                }
                ToolMode.NONE -> {}
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Isolates erasing operations to this view only
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        for (dp in paths) {
            canvas.drawPath(dp.path, dp.paint)
        }

        canvas.restoreToCount(saveCount)
    }
}