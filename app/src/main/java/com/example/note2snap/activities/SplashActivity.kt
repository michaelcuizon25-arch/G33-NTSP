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

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val ivLogo = findViewById<ImageView>(R.id.ivLogo)

        // Start ring, beam, and lens pulse animations
        startCameraLoadingAnimation(ivLogo)

        // Navigate to MainActivity after 2.5 seconds (2500ms)
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, 2500)
    }

    private fun startCameraLoadingAnimation(ivLogo: ImageView) {
        val ivScanRing = findViewById<ImageView>(R.id.ivScanRing)
        val vScanBeam = findViewById<View>(R.id.vScanBeam)

        // 1. Endless camera focus ring rotation (360 degrees)
        ObjectAnimator.ofFloat(ivScanRing, "rotation", 0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // 2. Scanner laser line bouncing up and down
        ObjectAnimator.ofFloat(vScanBeam, "translationY", -180f, 180f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // 3. Subtle lens pulse applied directly to the PNG logo
        ObjectAnimator.ofFloat(ivLogo, "scaleX", 0.94f, 1.06f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        ObjectAnimator.ofFloat(ivLogo, "scaleY", 0.94f, 1.06f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun navigateToMain() {
        if (!isFinishing) {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}