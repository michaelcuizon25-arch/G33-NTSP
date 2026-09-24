package com.example.note2snap.activities

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.note2snap.R
import com.google.android.material.bottomnavigation.BottomNavigationView

class CurvedBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BottomNavigationView(context, attrs, defStyleAttr) {

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var dipWidth = 0f
    private var dipDepth = 0f
    private var cornerRadius = 0f

    private var currentCenterX = -1f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        background = null
        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        paint.color = ContextCompat.getColor(context, R.color.nav_wave_color)

        val density = resources.displayMetrics.density
        // Adjusted dimensions for a smooth, natural cradle dip
        dipWidth = 90f * density
        dipDepth = 28f * density
        cornerRadius = 20f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (currentCenterX < 0) {
            currentCenterX = w / 2f
        }
    }

    fun animateCurveToItem(itemId: Int) {
        val menuView = getChildAt(0) as? ViewGroup ?: return

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId == itemId) {
                val itemView = menuView.getChildAt(i) ?: continue
                val targetX = itemView.x + (itemView.width / 2f)

                animator?.cancel()
                animator = ValueAnimator.ofFloat(currentCenterX, targetX).apply {
                    duration = 350
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { anim ->
                        currentCenterX = anim.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                break
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        if (currentCenterX < 0) {
            currentCenterX = width / 2f
        }

        path.reset()
        path.moveTo(cornerRadius, 0f)

        val halfDip = dipWidth / 2f
        val dipStart = (currentCenterX - halfDip).coerceAtLeast(cornerRadius)
        val dipEnd = (currentCenterX + halfDip).coerceAtMost(width - cornerRadius)

        // Draw line to start of center cradle
        path.lineTo(dipStart, 0f)

        // Smooth downward Bezier curve into cradle depth
        path.cubicTo(
            dipStart + (dipWidth / 3f), 0f,
            currentCenterX - (dipWidth / 3f), dipDepth,
            currentCenterX, dipDepth
        )

        // Smooth upward Bezier curve back to top line
        path.cubicTo(
            currentCenterX + (dipWidth / 3f), dipDepth,
            dipEnd - (dipWidth / 3f), 0f,
            dipEnd, 0f
        )

        // Continue top edge and rounded corners
        path.lineTo(width - cornerRadius, 0f)
        path.quadTo(width, 0f, width, cornerRadius)
        path.lineTo(width, height)
        path.lineTo(0f, height)
        path.lineTo(0f, cornerRadius)
        path.quadTo(0f, 0f, cornerRadius, 0f)

        path.close()

        canvas.drawPath(path, paint)
        super.onDraw(canvas)
    }

    fun setWaveColor(colorInt: Int) {
        paint.color = colorInt
        invalidate()
    }
}