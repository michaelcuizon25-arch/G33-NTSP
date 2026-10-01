package com.example.note2snap.tutorial

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R
import com.example.note2snap.activities.MainActivity

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "Note2SnapTutorial"
        private const val KEY_COMPLETED = "ONBOARDING_COMPLETED"
        const val KEY_START_SPOTLIGHT = "START_SPOTLIGHT_AFTER_ONBOARDING"
    }

    private lateinit var robot: LottieAnimationView
    private lateinit var tvSpeech: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var featureContainer: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView
    private lateinit var dots: List<View>

    private var pageIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var typingRunnable: Runnable? = null
    private var robotBob: ObjectAnimator? = null

    private data class Page(
        val speech: String,
        val title: String,
        val subtitle: String
    )

    private val pages = listOf(
        Page(
            speech = "Hi! I'm Snap, your Note2Snap guide. I'll help you turn whiteboard photos into organized notes!",
            title = "Welcome to\nNote2Snap",
            subtitle = "Capture or import whiteboard images in just a few taps."
        ),
        Page(
            speech = "Start by taking a photo of your whiteboard or choose one or several images from your gallery.",
            title = "Capture Your Notes",
            subtitle = "Take a photo or import existing whiteboard images from your gallery."
        ),
        Page(
            speech = "I'll recognize the writing, preserve important visual elements, and organize everything into a cleaner note.",
            title = "Turn Photos Into Notes",
            subtitle = "Note2Snap converts whiteboard content into editable, structured digital notes."
        ),
        Page(
            speech = "Review the result, fix anything you want, organize your notes, and export them whenever you need them.",
            title = "Your Notes,\nReady to Use",
            subtitle = "Edit, organize, save, and export your finished notes."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val forceShow =
            intent.getBooleanExtra(
                "FORCE_SHOW_ONBOARDING",
                false
            )

        if (
            !forceShow &&
            prefs.getBoolean(
                KEY_COMPLETED,
                false
            )
        ) {
            openMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        robot = findViewById(R.id.lottieOnboardingRobot)
        tvSpeech = findViewById(R.id.tvOnboardingSpeech)
        tvTitle = findViewById(R.id.tvOnboardingTitle)
        tvSubtitle = findViewById(R.id.tvOnboardingSubtitle)
        featureContainer = findViewById(R.id.onboardingFeatureContainer)
        btnBack = findViewById(R.id.btnOnboardingBack)
        btnNext = findViewById(R.id.btnOnboardingNext)
        btnSkip = findViewById(R.id.btnOnboardingSkip)

        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )

        robot.setAnimation(R.raw.robot_mascot)
        robot.repeatCount = ValueAnimator.INFINITE
        robot.playAnimation()

        startRobotBob()

        robot.setOnClickListener {
            robotTapReaction()
        }

        btnBack.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex--
                renderPage()
            }
        }

        btnNext.setOnClickListener {
            if (pageIndex < pages.lastIndex) {
                animateNext {
                    pageIndex++
                    renderPage()
                }
            } else {
                completeOnboarding()
            }
        }

        btnSkip.setOnClickListener {
            completeOnboarding()
        }

        renderPage()
    }

    private fun renderPage() {
        val page = pages[pageIndex]

        btnBack.visibility =
            if (pageIndex == 0) View.INVISIBLE else View.VISIBLE

        btnSkip.visibility =
            if (pageIndex == pages.lastIndex) View.INVISIBLE else View.VISIBLE

        btnNext.text =
            if (pageIndex == pages.lastIndex) {
                "Get Started"
            } else {
                "Next  →"
            }

        tvTitle.text = page.title
        tvSubtitle.text = page.subtitle

        typeSpeech(page.speech)
        updateDots()
        renderFeatureContent()
        animatePageEntrance()
    }

    private fun typeSpeech(message: String) {
        typingRunnable?.let {
            handler.removeCallbacks(it)
        }

        tvSpeech.text = ""
        var index = 0

        val runnable =
            object : Runnable {
                override fun run() {
                    if (index <= message.length) {
                        tvSpeech.text =
                            message.substring(
                                0,
                                index
                            )

                        index++

                        handler.postDelayed(
                            this,
                            18L
                        )
                    }
                }
            }

        typingRunnable = runnable
        handler.post(runnable)
    }

    private fun renderFeatureContent() {
        featureContainer.removeAllViews()

        when (pageIndex) {
            0 -> renderWelcomeAccent()
            1 -> renderCaptureChoices()
            2 -> renderBeforeAfter()
            3 -> renderFinalFeatures()
        }
    }

    private fun renderWelcomeAccent() {
        featureContainer.gravity = Gravity.CENTER

        featureContainer.addView(
            TextView(this).apply {
                text = "N+S"
                textSize = 34f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    ContextCompat.getColor(
                        this@OnboardingActivity,
                        R.color.nts_blue
                    )
                )
                setPadding(
                    dp(24),
                    dp(12),
                    dp(24),
                    dp(12)
                )
                background =
                    roundedBackground(
                        R.color.nts_blue_soft,
                        22
                    )
            }
        )
    }

    private fun renderCaptureChoices() {
        featureContainer.orientation = LinearLayout.HORIZONTAL
        featureContainer.gravity = Gravity.CENTER

        featureContainer.addView(
            miniCard(
                title = "Take a photo",
                subtitle = "Use the camera",
                symbol = "◉"
            ),
            weightedParams()
        )

        featureContainer.addView(
            spacer(12)
        )

        featureContainer.addView(
            miniCard(
                title = "Import",
                subtitle = "Choose multiple photos",
                symbol = "▣"
            ),
            weightedParams()
        )
    }

    private fun renderBeforeAfter() {
        featureContainer.orientation = LinearLayout.HORIZONTAL
        featureContainer.gravity = Gravity.CENTER

        featureContainer.addView(
            previewCard(
                label = "Whiteboard Photo",
                body = "Chapter 3\nAlgorithms\n- sorting\n- searching\n□ → □"
            ),
            weightedParams()
        )

        featureContainer.addView(
            TextView(this).apply {
                text = "→"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        this@OnboardingActivity,
                        R.color.nts_blue
                    )
                )
            },
            LinearLayout.LayoutParams(
                dp(38),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        featureContainer.addView(
            previewCard(
                label = "Structured Note",
                body = "ALGORITHMS\n• Sorting\n• Searching\n• Graph traversal\n□ → □"
            ),
            weightedParams()
        )
    }

    private fun renderFinalFeatures() {
        featureContainer.orientation = LinearLayout.VERTICAL
        featureContainer.gravity = Gravity.CENTER

        featureContainer.addView(
            featureRow(
                symbol = "✎",
                title = "Edit",
                subtitle = "Correct recognized text."
            )
        )

        featureContainer.addView(
            spacer(8)
        )

        featureContainer.addView(
            featureRow(
                symbol = "▣",
                title = "Organize",
                subtitle = "Save notes into folders."
            )
        )

        featureContainer.addView(
            spacer(8)
        )

        featureContainer.addView(
            featureRow(
                symbol = "PDF",
                title = "Export",
                subtitle = "Turn your notes into a PDF."
            )
        )
    }

    private fun miniCard(
        title: String,
        subtitle: String,
        symbol: String
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(12),
                dp(14),
                dp(12),
                dp(14)
            )
            background =
                roundedBackground(
                    R.color.nts_surface,
                    18,
                    R.color.nts_blue_line
                )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = symbol
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_blue
                        )
                    )
                }
            )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = title
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_text
                        )
                    )
                }
            )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = subtitle
                    textSize = 9.5f
                    gravity = Gravity.CENTER
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_text_secondary
                        )
                    )
                }
            )
        }
    }

    private fun previewCard(
        label: String,
        body: String
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )
            background =
                roundedBackground(
                    R.color.nts_surface,
                    18,
                    R.color.nts_blue_line
                )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = body
                    textSize = 10.5f
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_text
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = label
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_text_secondary
                        )
                    )
                }
            )
        }
    }

    private fun featureRow(
        symbol: String,
        title: String,
        subtitle: String
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(14),
                dp(11),
                dp(14),
                dp(11)
            )
            background =
                roundedBackground(
                    R.color.nts_surface,
                    16,
                    R.color.nts_blue_line
                )

            addView(
                TextView(this@OnboardingActivity).apply {
                    text = symbol
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            R.color.nts_blue
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    dp(44),
                    dp(44)
                )
            )

            addView(
                LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL

                    addView(
                        TextView(this@OnboardingActivity).apply {
                            text = title
                            textSize = 12.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(
                                ContextCompat.getColor(
                                    this@OnboardingActivity,
                                    R.color.nts_text
                                )
                            )
                        }
                    )

                    addView(
                        TextView(this@OnboardingActivity).apply {
                            text = subtitle
                            textSize = 9.5f
                            setTextColor(
                                ContextCompat.getColor(
                                    this@OnboardingActivity,
                                    R.color.nts_text_secondary
                                )
                            )
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
    }

    private fun updateDots() {
        dots.forEachIndexed {
                index,
                dot ->

            dot.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        ContextCompat.getColor(
                            this@OnboardingActivity,
                            if (index == pageIndex) {
                                R.color.nts_blue
                            } else {
                                R.color.nts_blue_line
                            }
                        )
                    )
                }
        }
    }

    private fun animatePageEntrance() {
        tvTitle.alpha = 0f
        tvSubtitle.alpha = 0f
        featureContainer.alpha = 0f

        tvTitle.translationY = dp(10).toFloat()
        tvSubtitle.translationY = dp(10).toFloat()
        featureContainer.translationY = dp(14).toFloat()

        tvTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()

        tvSubtitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(50L)
            .setDuration(220L)
            .start()

        featureContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100L)
            .setDuration(260L)
            .start()
    }

    private fun animateNext(
        onEnd: () -> Unit
    ) {
        robot.animate()
            .translationX(dp(18).toFloat())
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(130L)
            .withEndAction {
                robot.animate()
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .withEndAction {
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    private fun robotTapReaction() {
        val scaleX =
            ObjectAnimator.ofFloat(
                robot,
                View.SCALE_X,
                1f,
                1.09f,
                0.96f,
                1f
            )

        val scaleY =
            ObjectAnimator.ofFloat(
                robot,
                View.SCALE_Y,
                1f,
                1.09f,
                0.96f,
                1f
            )

        AnimatorSet().apply {
            playTogether(
                scaleX,
                scaleY
            )
            duration = 360L
            start()
        }
    }

    private fun startRobotBob() {
        robotBob?.cancel()

        robotBob =
            ObjectAnimator.ofFloat(
                robot,
                View.TRANSLATION_Y,
                0f,
                -dp(7).toFloat(),
                0f
            ).apply {
                duration = 1900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator =
                    AccelerateDecelerateInterpolator()
                start()
            }
    }

    private fun completeOnboarding() {
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(
                KEY_COMPLETED,
                true
            )
            .putBoolean(
                KEY_START_SPOTLIGHT,
                true
            )
            .apply()

        openMain()
    }

    private fun openMain() {
        startActivity(
            Intent(
                this,
                MainActivity::class.java
            )
        )
        finish()
    }

    private fun weightedParams() =
        LinearLayout.LayoutParams(
            0,
            dp(142),
            1f
        )

    private fun spacer(dp: Int) =
        View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    dp(dp),
                    dp(dp)
                )
        }

    private fun roundedBackground(
        fillColorRes: Int,
        radiusDp: Int,
        strokeColorRes: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius =
                dp(radiusDp).toFloat()

            setColor(
                ContextCompat.getColor(
                    this@OnboardingActivity,
                    fillColorRes
                )
            )

            if (strokeColorRes != null) {
                setStroke(
                    dp(1),
                    ContextCompat.getColor(
                        this@OnboardingActivity,
                        strokeColorRes
                    )
                )
            }
        }
    }

    private fun dp(value: Int): Int =
        (
                value *
                        resources.displayMetrics.density
                ).toInt()

    override fun onDestroy() {
        typingRunnable?.let {
            handler.removeCallbacks(it)
        }
        robotBob?.cancel()
        robot.cancelAnimation()
        super.onDestroy()
    }
}
