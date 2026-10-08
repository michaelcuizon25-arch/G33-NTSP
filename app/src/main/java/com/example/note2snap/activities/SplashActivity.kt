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
import android.widget.TextView
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

        val robot =
            findViewById<LottieAnimationView>(
                R.id.splashRobot
            )

        val beam =
            findViewById<View>(
                R.id.scanBeam
            )

        val boardContent =
            findViewById<View>(
                R.id.whiteboardContent
            )

        val structuredNote =
            findViewById<View>(
                R.id.structuredNotePreview
            )

        val status =
            findViewById<TextView>(
                R.id.tvSplashStatus
            )

        robot.playAnimation()

        beam.post {
            val travel =
                (
                    findViewById<View>(
                        R.id.whiteboardScene
                    ).height -
                        beam.height -
                        40
                    )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val scanDown =
                ObjectAnimator.ofFloat(
                    beam,
                    View.TRANSLATION_Y,
                    0f,
                    travel
                ).apply {
                    duration =
                        650L

                    interpolator =
                        AccelerateDecelerateInterpolator()
                }

            val fadeBoard =
                ObjectAnimator.ofFloat(
                    boardContent,
                    View.ALPHA,
                    1f,
                    0.18f
                ).apply {
                    duration =
                        260L
                }

            val showNote =
                ObjectAnimator.ofFloat(
                    structuredNote,
                    View.ALPHA,
                    0f,
                    1f
                ).apply {
                    duration =
                        320L
                }

            AnimatorSet().apply {
                play(
                    scanDown
                ).before(
                    fadeBoard
                )

                play(
                    fadeBoard
                ).with(
                    showNote
                )

                start()
            }

            handler.postDelayed(
                {
                    status.text =
                        "Structured and ready."
                },
                780L
            )
        }

        handler.postDelayed(
            openAppRunnable,
            1450L
        )
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
            ).getBoolean(
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
