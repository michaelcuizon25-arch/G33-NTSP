package com.example.note2snap.tutorial

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R

data class TutorialStep(
    val title: String,
    val description: String,
    val targetView: View? = null
)

class TutorialManager(
    private val activity: Activity
) {

    private val steps =
        mutableListOf<TutorialStep>()

    private var current =
        0

    private var overlay:
        GuideOverlay? =
        null

    private var onFinished:
        (() -> Unit)? =
        null

    fun addStep(
        step: TutorialStep
    ): TutorialManager {
        steps.add(step)
        return this
    }

    fun start(
        onComplete: (() -> Unit)? = null
    ) {
        onFinished =
            onComplete

        if (steps.isEmpty()) {
            onFinished?.invoke()
            return
        }

        current =
            0

        showCurrent()
    }

    fun dismiss() {
        overlay?.stopAnimations()

        overlay?.let {
            (it.parent as? ViewGroup)
                ?.removeView(it)
        }

        overlay =
            null
    }

    private fun showCurrent() {
        if (
            current >=
            steps.size
        ) {
            dismiss()
            onFinished?.invoke()
            return
        }

        if (overlay == null) {
            val root =
                activity.window.decorView
                    as ViewGroup

            overlay =
                GuideOverlay(
                    activity = activity,
                    onNext = {
                        current++
                        showCurrent()
                    },
                    onSkip = {
                        dismiss()
                        onFinished?.invoke()
                    }
                )

            root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        overlay?.showStep(
            step = steps[current],
            index = current + 1,
            total = steps.size
        )
    }

    private class GuideOverlay(
        activity: Activity,
        private val onNext: () -> Unit,
        private val onSkip: () -> Unit
    ) : FrameLayout(activity) {

        private val spotlight =
            SpotlightTutorialView(activity)

        private val robot =
            LottieAnimationView(activity)

        private val bubble =
            LinearLayout(activity)

        private val tvStep =
            TextView(activity)

        private val tvTitle =
            TextView(activity)

        private val tvBody =
            TextView(activity)

        private val btnSkip =
            TextView(activity)

        private val btnNext =
            TextView(activity)

        private var bob:
            ObjectAnimator? =
            null

        init {
            isClickable = true
            isFocusable = true

            addView(
                spotlight,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            )

            robot.apply {
                setAnimation(
                    R.raw.robot_mascot
                )
                repeatCount =
                    ValueAnimator.INFINITE
                playAnimation()
            }

            addView(
                robot,
                LayoutParams(
                    dp(124),
                    dp(124)
                )
            )

            bubble.orientation =
                LinearLayout.VERTICAL

            bubble.setPadding(
                dp(18),
                dp(15),
                dp(18),
                dp(14)
            )

            bubble.background =
                GradientDrawable().apply {
                    shape =
                        GradientDrawable.RECTANGLE

                    cornerRadius =
                        dp(22).toFloat()

                    setColor(
                        Color.parseColor(
                            "#FFFDFEFF"
                        )
                    )

                    setStroke(
                        dp(1),
                        Color.parseColor(
                            "#D7E2F6"
                        )
                    )
                }

            tvStep.apply {
                textSize = 10.5f
                typeface =
                    Typeface.DEFAULT_BOLD
                setTextColor(
                    Color.parseColor(
                        "#7896EE"
                    )
                )
            }

            tvTitle.apply {
                textSize = 20f
                typeface =
                    Typeface.create(
                        "serif",
                        Typeface.BOLD
                    )
                setTextColor(
                    Color.parseColor(
                        "#151923"
                    )
                )
                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(4)
                )
            }

            tvBody.apply {
                textSize = 13f
                setTextColor(
                    Color.parseColor(
                        "#586172"
                    )
                )
            }

            val actions =
                LinearLayout(
                    activity
                ).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                    gravity =
                        Gravity.END
                    setPadding(
                        0,
                        dp(14),
                        0,
                        0
                    )
                }

            btnSkip.apply {
                text =
                    "Skip"
                gravity =
                    Gravity.CENTER
                textSize =
                    12f
                setTextColor(
                    Color.parseColor(
                        "#6F8FEA"
                    )
                )
                setPadding(
                    dp(16),
                    dp(9),
                    dp(16),
                    dp(9)
                )
                setOnClickListener {
                    onSkip()
                }
            }

            btnNext.apply {
                text =
                    "Next  →"
                gravity =
                    Gravity.CENTER
                textSize =
                    12f
                typeface =
                    Typeface.DEFAULT_BOLD
                setTextColor(
                    Color.WHITE
                )
                setPadding(
                    dp(20),
                    dp(10),
                    dp(20),
                    dp(10)
                )

                background =
                    GradientDrawable().apply {
                        cornerRadius =
                            dp(18).toFloat()

                        setColor(
                            Color.parseColor(
                                "#6F8FEA"
                            )
                        )
                    }

                setOnClickListener {
                    onNext()
                }
            }

            actions.addView(
                btnSkip
            )

            actions.addView(
                btnNext
            )

            bubble.addView(
                tvStep
            )
            bubble.addView(
                tvTitle
            )
            bubble.addView(
                tvBody
            )
            bubble.addView(
                actions
            )

            addView(
                bubble,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin =
                        dp(24)
                    rightMargin =
                        dp(24)
                }
            )

            startBob()
        }

        fun showStep(
            step: TutorialStep,
            index: Int,
            total: Int
        ) {
            tvStep.text =
                "Step $index of $total"

            tvTitle.text =
                step.title

            tvBody.text =
                step.description

            btnNext.text =
                if (index == total) {
                    "Got it  ✓"
                } else {
                    "Next  →"
                }

            bubble.animate()
                .cancel()

            bubble.alpha =
                0f

            bubble.translationY =
                dp(
                    10
                ).toFloat()

            bubble.animate()
                .alpha(
                    1f
                )
                .translationY(
                    0f
                )
                .setDuration(
                    190L
                )
                .start()

            robot.animate()
                .cancel()

            robot.scaleX =
                0.9f

            robot.scaleY =
                0.9f

            robot.rotation =
                -3f

            robot.animate()
                .scaleX(
                    1f
                )
                .scaleY(
                    1f
                )
                .rotation(
                    0f
                )
                .setDuration(
                    280L
                )
                .setInterpolator(
                    OvershootInterpolator(
                        1.25f
                    )
                )
                .start()

            val target =
                step.targetView

            if (
                target != null &&
                target.isShown
            ) {
                spotlight.setTargetView(
                    target,
                    12f
                )

                target.post {
                    val screen =
                        IntArray(2)

                    target.getLocationOnScreen(
                        screen
                    )

                    val centerY =
                        screen[1] +
                            target.height / 2

                    if (
                        centerY <
                        height / 2
                    ) {
                        placeBelow()
                    } else {
                        placeAbove()
                    }
                }
            } else {
                spotlight.setTargetView(
                    null
                )
                placeCentered()
            }
        }

        private fun placeBelow() {
            val rp =
                robot.layoutParams
                    as LayoutParams

            rp.gravity =
                Gravity.BOTTOM or
                    Gravity.START

            rp.leftMargin =
                dp(24)

            rp.bottomMargin =
                dp(230)

            rp.topMargin =
                0

            robot.layoutParams =
                rp

            val bp =
                bubble.layoutParams
                    as LayoutParams

            bp.gravity =
                Gravity.BOTTOM

            bp.bottomMargin =
                dp(54)

            bp.topMargin =
                0

            bubble.layoutParams =
                bp
        }

        private fun placeAbove() {
            val rp =
                robot.layoutParams
                    as LayoutParams

            rp.gravity =
                Gravity.TOP or
                    Gravity.START

            rp.leftMargin =
                dp(24)

            rp.topMargin =
                dp(86)

            rp.bottomMargin =
                0

            robot.layoutParams =
                rp

            val bp =
                bubble.layoutParams
                    as LayoutParams

            bp.gravity =
                Gravity.TOP

            bp.topMargin =
                dp(210)

            bp.bottomMargin =
                0

            bubble.layoutParams =
                bp
        }

        private fun placeCentered() {
            val rp =
                robot.layoutParams
                    as LayoutParams

            rp.gravity =
                Gravity.CENTER_HORIZONTAL or
                    Gravity.TOP

            rp.topMargin =
                dp(130)

            robot.layoutParams =
                rp

            val bp =
                bubble.layoutParams
                    as LayoutParams

            bp.gravity =
                Gravity.CENTER

            bp.topMargin =
                dp(130)

            bubble.layoutParams =
                bp
        }

        private fun startBob() {
            bob?.cancel()

            bob =
                ObjectAnimator.ofFloat(
                    robot,
                    View.TRANSLATION_Y,
                    0f,
                    -dp(7).toFloat(),
                    0f
                ).apply {
                    duration =
                        1800L

                    repeatCount =
                        ValueAnimator.INFINITE

                    interpolator =
                        AccelerateDecelerateInterpolator()

                    start()
                }
        }

        fun stopAnimations() {
            bob?.cancel()
            robot.cancelAnimation()
        }

        private fun dp(
            value: Int
        ): Int {
            return (
                value *
                    resources
                        .displayMetrics
                        .density
                ).toInt()
        }
    }
}
