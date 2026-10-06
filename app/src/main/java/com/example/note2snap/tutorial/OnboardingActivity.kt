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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R
import com.example.note2snap.activities.MainActivity
import com.example.note2snap.activities.LegalAgreementActivity

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "Note2SnapOnboardingInteractiveV5"
        private const val KEY_DONE = "ONBOARDING_INTERACTIVE_V5_DONE"
    }

    private lateinit var robot: LottieAnimationView
    private lateinit var speech: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var features: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView
    private lateinit var skip: TextView
    private lateinit var dots: List<View>

    private val handler = Handler(Looper.getMainLooper())
    private var typingTask: Runnable? = null
    private var robotBob: ObjectAnimator? = null
    private var page = 0

    private data class Page(
        val speech: String,
        val title: String,
        val subtitle: String
    )

    private val pages = listOf(
        Page(
            "Hi! I'm Snap, your Note2Snap guide. I'll show you how to turn whiteboard photos into organized notes.",
            "Welcome to\nNote2Snap",
            "Capture or import whiteboard images in just a few taps."
        ),
        Page(
            "Start by taking a photo of your whiteboard, or choose one or several images from your gallery.",
            "Capture Your Notes",
            "Use the camera or import multiple whiteboard photos from your gallery."
        ),
        Page(
            "I'll recognize the writing, keep important visuals, and organize everything into a cleaner structured note.",
            "Turn Photos Into Notes",
            "From a whiteboard photo to a cleaner, editable digital note."
        ),
        Page(
            "Review the result, fix anything you want, organize your notes, then export or share them whenever you need.",
            "Your Notes,\nReady to Use",
            "Edit, organize, save, export, and share your finished notes."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
        ) {
            openMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        robot = findViewById(R.id.lottieOnboardingRobot)
        speech = findViewById(R.id.tvOnboardingSpeech)
        title = findViewById(R.id.tvOnboardingTitle)
        subtitle = findViewById(R.id.tvOnboardingSubtitle)
        features = findViewById(R.id.onboardingFeatureContainer)
        back = findViewById(R.id.btnOnboardingBack)
        next = findViewById(R.id.btnOnboardingNext)
        skip = findViewById(R.id.btnOnboardingSkip)

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
            showRobotReactionMessage()
        }

        back.setOnClickListener {
            if (page > 0) {
                page--
                transitionPage(forward = false)
            }
        }

        next.setOnClickListener {
            if (page < pages.lastIndex) {
                page++
                transitionPage(forward = true)
            } else {
                finishOnboarding()
            }
        }

        skip.setOnClickListener {
            finishOnboarding()
        }

        renderPage()
    }

    private fun renderPage() {
        val item = pages[page]

        back.visibility =
            if (page == 0) View.INVISIBLE else View.VISIBLE

        skip.visibility =
            if (page == pages.lastIndex) View.INVISIBLE else View.VISIBLE

        next.text =
            if (page == pages.lastIndex) "Get Started  →" else "Next  →"

        title.text = item.title
        subtitle.text = item.subtitle
        typeSpeech(item.speech)
        renderFeatures()
        updateDots()
        reactRobotForPage()
    }

    private fun renderFeatures() {
        features.removeAllViews()

        when (page) {
            0 -> {
                features.orientation = LinearLayout.HORIZONTAL
                features.gravity = Gravity.CENTER
                features.addView(featureChip(R.drawable.ic_onboard_camera, "Capture"), weightParams())
                features.addView(gap(8))
                features.addView(featureChip(R.drawable.ic_onboard_gallery, "Import"), weightParams())
                features.addView(gap(8))
                features.addView(featureChip(R.drawable.ic_onboard_pdf, "Export"), weightParams())
            }

            1 -> {
                features.orientation = LinearLayout.HORIZONTAL
                features.gravity = Gravity.CENTER
                features.addView(actionCard(R.drawable.ic_onboard_camera, "Take a photo", "Use the camera"), largeWeight())
                features.addView(gap(12))
                features.addView(actionCard(R.drawable.ic_onboard_gallery, "Import", "Choose multiple photos"), largeWeight())
            }

            2 -> {
                features.orientation = LinearLayout.HORIZONTAL
                features.gravity = Gravity.CENTER
                features.addView(noteCard("Whiteboard Photo", "STUDY PLAN\n• Review Chapter 3\n• Make reviewer\n• Practice problems\n\nIdea → Draft → Done"), largeWeight())
                features.addView(
                    TextView(this).apply {
                        text = "→"
                        textSize = 24f
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_blue))
                    },
                    LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT)
                )
                features.addView(noteCard("Structured Note", "STUDY PLAN\nKey Tasks\n• Review Chapter 3\n• Make reviewer\n• Practice problems\n\nProgress\nIdea → Draft → Done"), largeWeight())
            }

            3 -> {
                features.orientation = LinearLayout.VERTICAL
                features.gravity = Gravity.CENTER
                val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

                r1.addView(featureTile(R.drawable.ic_onboard_edit, "Edit", "Correct recognized text"), tileWeight())
                r1.addView(gap(10))
                r1.addView(featureTile(R.drawable.ic_onboard_folder, "Organize", "Save into folders"), tileWeight())

                r2.addView(featureTile(R.drawable.ic_onboard_pdf, "Export PDF", "Create a PDF copy"), tileWeight())
                r2.addView(gap(10))
                r2.addView(featureTile(R.drawable.ic_onboard_share, "Share", "Send to other apps"), tileWeight())

                features.addView(r1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                features.addView(verticalGap(8))
                features.addView(r2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
    }

    private fun transitionPage(forward: Boolean) {
        val distance = if (forward) dp(28).toFloat() else -dp(28).toFloat()

        listOf(robot, features, title, subtitle).forEach {
            it.animate()
                .alpha(0f)
                .translationX(-distance)
                .setDuration(120L)
                .withEndAction {
                    renderPage()
                    it.translationX = distance
                    it.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(190L)
                        .start()
                }
                .start()
        }
    }

    private fun reactRobotForPage() {
        when (page) {
            0 -> robot.rotation = -2f
            1 -> robot.rotation = 2f
            2 -> robot.rotation = -1f
            3 -> robot.rotation = 1f
        }

        robot.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(180L)
            .withEndAction {
                robot.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
    }

    private fun showRobotReactionMessage() {
        val reactions = when (page) {
            0 -> listOf("Ready? Let's go! ✨", "Tap Next and I'll show you around!", "Hi again! 👋")
            1 -> listOf("Camera or gallery — your choice!", "You can import multiple photos too!")
            2 -> listOf("This is where the magic happens ✨", "I'll keep the important visuals too!")
            else -> listOf("Almost done! 🎉", "Your notes are ready when you are!")
        }

        val original = pages[page].speech
        val reaction = reactions[(System.currentTimeMillis() % reactions.size).toInt()]

        typingTask?.let(handler::removeCallbacks)
        speech.text = reaction

        handler.postDelayed(
            {
                typeSpeech(original)
            },
            1200L
        )
    }

    private fun typeSpeech(message: String) {
        typingTask?.let(handler::removeCallbacks)
        speech.text = ""
        var index = 0

        val task = object : Runnable {
            override fun run() {
                if (index <= message.length) {
                    speech.text = message.substring(0, index)
                    index++
                    handler.postDelayed(this, 14L)
                }
            }
        }

        typingTask = task
        handler.post(task)
    }

    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    ContextCompat.getColor(
                        this@OnboardingActivity,
                        if (index == page) R.color.nts_blue else R.color.nts_blue_line
                    )
                )
            }
        }
    }

    private fun startRobotBob() {
        robotBob?.cancel()
        robotBob =
            ObjectAnimator.ofFloat(
                robot,
                View.TRANSLATION_Y,
                0f,
                -dp(8).toFloat(),
                0f
            ).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
    }

    private fun robotTapReaction() {
        val sx = ObjectAnimator.ofFloat(robot, View.SCALE_X, 1f, 1.10f, 0.97f, 1f)
        val sy = ObjectAnimator.ofFloat(robot, View.SCALE_Y, 1f, 1.10f, 0.97f, 1f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 360L
            start()
        }
    }

    private fun featureChip(icon: Int, label: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = cardBackground()

            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(34), dp(34)))

            addView(TextView(this@OnboardingActivity).apply {
                text = label
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text))
            })
        }

    private fun actionCard(icon: Int, heading: String, body: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground()

            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(52), dp(52)))

            addView(TextView(this@OnboardingActivity).apply {
                text = heading
                textSize = 13f
                typeface = Typeface.create("serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text))
            })

            addView(TextView(this@OnboardingActivity).apply {
                text = body
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text_secondary))
            })
        }

    private fun noteCard(label: String, body: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cardBackground()

            addView(TextView(this@OnboardingActivity).apply {
                text = label
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_blue))
            })

            addView(TextView(this@OnboardingActivity).apply {
                text = body
                textSize = 9.5f
                setPadding(dp(4), dp(8), dp(4), dp(4))
                setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    private fun featureTile(icon: Int, heading: String, body: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = cardBackground()

            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(34), dp(34)))

            addView(LinearLayout(this@OnboardingActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)

                addView(TextView(this@OnboardingActivity).apply {
                    text = heading
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text))
                })

                addView(TextView(this@OnboardingActivity).apply {
                    text = body
                    textSize = 8.5f
                    setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_text_secondary))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun cardBackground() =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(ContextCompat.getColor(this@OnboardingActivity, R.color.nts_surface))
            setStroke(dp(1), ContextCompat.getColor(this@OnboardingActivity, R.color.nts_blue_line))
        }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(104), 1f)
    private fun largeWeight() = LinearLayout.LayoutParams(0, dp(146), 1f)
    private fun tileWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    private fun gap(v: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(v), 1) }
    private fun verticalGap(v: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(v)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()

        // Fresh guide flags: Home and Camera will each show their tutorial once.
        getSharedPreferences("Note2SnapGuideV5", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("HOME_GUIDE_V5_SHOWN", false)
            .putBoolean("CAMERA_GUIDE_V5_SHOWN", false)
            .apply()

        openMain()
    }

    private fun openMain() {
        val legalAccepted =
            getSharedPreferences(
                LegalAgreementActivity.PREFS,
                Context.MODE_PRIVATE
            ).getBoolean(
                LegalAgreementActivity.KEY_ACCEPTED,
                false
            )

        val destination =
            if (legalAccepted) {
                MainActivity::class.java
            } else {
                LegalAgreementActivity::class.java
            }

        startActivity(
            Intent(
                this,
                destination
            )
        )

        finish()
    }

    override fun onDestroy() {
        typingTask?.let(handler::removeCallbacks)
        robotBob?.cancel()
        robot.cancelAnimation()
        super.onDestroy()
    }
}
