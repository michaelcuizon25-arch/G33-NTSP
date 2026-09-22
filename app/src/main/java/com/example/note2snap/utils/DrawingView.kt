package com.example.note2snap.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class ToolMode { PEN, HIGHLIGHTER, ERASER }

data class Stroke(
    val path: Path,
    val paint: Paint
)

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokes = mutableListOf<Stroke>()
    private var currentPath = Path()
    private var currentPaint = Paint()

    var currentTool: ToolMode = ToolMode.PEN
        private set

    var penColor: Int = Color.BLACK
        private set

    var highlighterColor: Int = Color.argb(100, 255, 235, 59) // Default transparent yellow
        private set

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for PorterDuff CLEAR (Eraser)
        setupPaint()
    }

    private fun setupPaint() {
        currentPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        when (currentTool) {
            ToolMode.PEN -> {
                currentPaint.color = penColor
                currentPaint.strokeWidth = 6f
                currentPaint.xfermode = null
            }
            ToolMode.HIGHLIGHTER -> {
                currentPaint.color = highlighterColor
                currentPaint.strokeWidth = 32f
                currentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            ToolMode.ERASER -> {
                currentPaint.color = Color.TRANSPARENT
                currentPaint.strokeWidth = 40f
                currentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
    }

    fun setTool(tool: ToolMode) {
        currentTool = tool
        setupPaint()
    }

    fun setPenColor(color: Int) {
        penColor = color
        if (currentTool == ToolMode.PEN) setupPaint()
    }

    fun setHighlighterColor(baseColor: Int) {
        // Apply 35% opacity (89 out of 255 alpha) so note text underneath is visible
        highlighterColor = Color.argb(89, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        if (currentTool == ToolMode.HIGHLIGHTER) setupPaint()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, stroke.paint)
        }
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                strokes.add(Stroke(currentPath, Paint(currentPaint)))
                currentPath = Path()
                invalidate()
            }
        }
        return true
    }

    fun clearCanvas() {
        strokes.clear()
        currentPath.reset()
        invalidate()
    }
}