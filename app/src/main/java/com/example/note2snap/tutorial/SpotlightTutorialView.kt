package com.example.note2snap.tutorial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SpotlightTutorialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AFC4F6")
        strokeWidth = dp(2f)
    }

    private var targetRect: RectF? = null
    private val cornerRadius = dp(16f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun setTargetView(
        targetView: View?,
        paddingDp: Float = 12f
    ) {
        if (targetView == null) {
            targetRect = null
            invalidate()
            return
        }

        targetView.post {
            if (!targetView.isShown) {
                targetRect = null
                invalidate()
                return@post
            }

            val targetLocation = IntArray(2)
            val ownLocation = IntArray(2)

            targetView.getLocationInWindow(targetLocation)
            getLocationInWindow(ownLocation)

            val paddingPx = dp(paddingDp)

            val left = targetLocation[0] - ownLocation[0] - paddingPx
            val top = targetLocation[1] - ownLocation[1] - paddingPx

            targetRect = RectF(
                left,
                top,
                left + targetView.width + (paddingPx * 2f),
                top + targetView.height + (paddingPx * 2f)
            )

            invalidate()
        }
    }

    fun clearTarget() {
        targetRect = null
        invalidate()
    }

    fun getTargetRect(): RectF? {
        return targetRect?.let { RectF(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            backgroundPaint
        )

        targetRect?.let { rect ->
            canvas.drawRoundRect(
                rect,
                cornerRadius,
                cornerRadius,
                eraserPaint
            )

            canvas.drawRoundRect(
                rect,
                cornerRadius,
                cornerRadius,
                strokePaint
            )
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
