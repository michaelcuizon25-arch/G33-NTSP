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
import androidx.core.graphics.toColorInt

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
    private var highlighterColor: Int = "#FFFF00".toColorInt()

    private val paths = mutableListOf<DrawnPath>()
    private val undonePaths = mutableListOf<DrawnPath>()

    private var currentPath: Path? = null

    data class DrawnPath(
        val path: Path,
        val paint: Paint
    )

    init {
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
                paths.add(DrawnPath(newPath, newPaint))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                    strokeWidth = 30f
                }
                ToolMode.ERASER -> {
                    color = Color.TRANSPARENT
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    strokeWidth = 40f
                }
                ToolMode.NONE -> {}
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (dp in paths) {
            canvas.drawPath(dp.path, dp.paint)
        }
    }
}