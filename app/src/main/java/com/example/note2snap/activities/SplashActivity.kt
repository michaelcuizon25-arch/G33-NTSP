package com.example.note2snap.activities

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.note2snap.R
import com.example.note2snap.tutorial.OnboardingActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val ivLogo =
            findViewById<ImageView>(R.id.ivLogo)

        startCameraLoadingAnimation(ivLogo)

        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    openOnboarding()
                },
                2500L
            )
    }

    private fun startCameraLoadingAnimation(
        ivLogo: ImageView
    ) {
        val ivScanRing =
            findViewById<ImageView>(R.id.ivScanRing)

        val vScanBeam =
            findViewById<View>(R.id.vScanBeam)

        ObjectAnimator.ofFloat(
            ivScanRing,
            "rotation",
            0f,
            360f
        ).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(
            vScanBeam,
            "translationY",
            -180f,
            180f
        ).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator =
                AccelerateDecelerateInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(
            ivLogo,
            "scaleX",
            0.94f,
            1.06f
        ).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        ObjectAnimator.ofFloat(
            ivLogo,
            "scaleY",
            0.94f,
            1.06f
        ).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun openOnboarding() {
        if (isFinishing) return

        startActivity(
            Intent(
                this,
                OnboardingActivity::class.java
            ).apply {
                putExtra(
                    "FORCE_SHOW_ONBOARDING",
                    true
                )
            }
        )

        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )

        finish()
    }
}
