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
) : View(
    context,
    attrs,
    defStyleAttr
) {

    private val dimPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.parseColor(
                    "#B80E1A32"
                )
        }

    private val clearPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            xfermode =
                PorterDuffXfermode(
                    PorterDuff.Mode.CLEAR
                )
        }

    private val borderPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                2.5f *
                    resources
                        .displayMetrics
                        .density

            color =
                Color.parseColor(
                    "#B8CBFF"
                )
        }

    private var target:
        RectF? =
        null

    init {
        setLayerType(
            LAYER_TYPE_SOFTWARE,
            null
        )
    }

    fun setTargetView(
        targetView: View?,
        paddingDp: Float = 12f
    ) {
        if (
            targetView == null
        ) {
            target =
                null
            invalidate()
            return
        }

        targetView.post {
            val pos =
                IntArray(2)

            targetView.getLocationOnScreen(
                pos
            )

            val own =
                IntArray(2)

            getLocationOnScreen(
                own
            )

            val pad =
                paddingDp *
                    resources
                        .displayMetrics
                        .density

            target =
                RectF(
                    pos[0] -
                        own[0] -
                        pad,
                    pos[1] -
                        own[1] -
                        pad,
                    pos[0] -
                        own[0] +
                        targetView.width +
                        pad,
                    pos[1] -
                        own[1] +
                        targetView.height +
                        pad
                )

            invalidate()
        }
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(
            canvas
        )

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            dimPaint
        )

        target?.let {
            val radius =
                24f *
                    resources
                        .displayMetrics
                        .density

            canvas.drawRoundRect(
                it,
                radius,
                radius,
                clearPaint
            )

            canvas.drawRoundRect(
                it,
                radius,
                radius,
                borderPaint
            )
        }
    }
}
