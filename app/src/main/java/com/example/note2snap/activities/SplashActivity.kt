package com.example.note2snap.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R
import com.example.note2snap.tutorial.OnboardingActivity

class SplashActivity : AppCompatActivity() {

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val openAppRunnable =
        Runnable {
            openNextScreen()
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_splash
        )

        val scene =
            findViewById<View>(
                R.id.splashScene
            )

        val robot =
            findViewById<LottieAnimationView>(
                R.id.splashRobot
            )

        val board =
            findViewById<View>(
                R.id.rawBoardContent
            )

        val beam =
            findViewById<View>(
                R.id.scanBeam
            )

        val note =
            findViewById<View>(
                R.id.structuredNote
            )

        scene.alpha =
            0f

        scene.translationY =
            8f *
                    resources
                        .displayMetrics
                        .density

        scene.animate()
            .alpha(
                1f
            )
            .translationY(
                0f
            )
            .setDuration(
                220L
            )
            .start()

        robot.playAnimation()

        beam.post {
            val boardHeight =
                findViewById<View>(
                    R.id.whiteboardCard
                ).height

            val travel =
                (
                        boardHeight -
                                beam.height -
                                dp(
                                    36
                                )
                        )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val scan =
                ObjectAnimator.ofFloat(
                    beam,
                    "translationY",
                    0f,
                    travel
                ).apply {

                    duration =
                        560L

                    interpolator =
                        AccelerateDecelerateInterpolator()
                }

            val fadeRaw =
                ObjectAnimator.ofFloat(
                    board,
                    "alpha",
                    1f,
                    0.16f
                ).apply {

                    duration =
                        220L
                }

            note.translationX =
                dp(
                    20
                )
                    .toFloat()

            val revealNote =
                ObjectAnimator.ofFloat(
                    note,
                    "alpha",
                    0f,
                    1f
                ).apply {

                    duration =
                        260L
                }

            val slideNote =
                ObjectAnimator.ofFloat(
                    note,
                    "translationX",
                    dp(
                        20
                    )
                        .toFloat(),
                    0f
                ).apply {

                    duration =
                        260L
                }

            AnimatorSet().apply {

                play(
                    scan
                ).before(
                    fadeRaw
                )

                play(
                    fadeRaw
                ).with(
                    revealNote
                )

                play(
                    revealNote
                ).with(
                    slideNote
                )

                start()
            }
        }

        handler.postDelayed(
            openAppRunnable,
            1320L
        )
    }

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources
                            .displayMetrics
                            .density
                )
            .toInt()
    }

    private fun openNextScreen() {

        if (
            isFinishing
        ) {
            return
        }

        val completed =
            getSharedPreferences(
                "Note2SnapOnboardingInteractiveV5",
                Context.MODE_PRIVATE
            )
                .getBoolean(
                    "ONBOARDING_INTERACTIVE_V5_DONE",
                    false
                )

        val destination =
            if (
                completed
            ) {
                MainActivity::class.java
            } else {
                OnboardingActivity::class.java
            }

        startActivity(
            Intent(
                this,
                destination
            )
        )

        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )

        finish()
    }

    override fun onDestroy() {

        handler.removeCallbacks(
            openAppRunnable
        )

        handler.removeCallbacksAndMessages(
            null
        )

        super.onDestroy()
    }
}
