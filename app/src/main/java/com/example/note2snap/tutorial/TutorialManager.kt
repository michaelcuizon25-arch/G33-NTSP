package com.example.note2snap.tutorial

import android.app.Activity
import android.view.View
import androidx.annotation.DrawableRes

/**
 * Legacy compatibility class.
 *
 * The old HomeFragment still calls TutorialManager in some copies of the project.
 * This version intentionally DOES NOT draw the old 1-of-3 overlay.
 *
 * Keep this file until the old Home tutorial call has been completely removed
 * from every source copy.
 */
data class TutorialStep(
    val title: String,
    val description: String,
    val targetView: View? = null,
    @DrawableRes val robotDrawableRes: Int? = null,
    val hint: String = "Tap anywhere to continue"
)

class TutorialManager(
    private val activity: Activity,
    @DrawableRes private val defaultRobotDrawableRes: Int? = null
) {

    fun addStep(
        step: TutorialStep
    ): TutorialManager {
        return this
    }

    fun clearSteps():
            TutorialManager {
        return this
    }

    /**
     * Disabled legacy overlay.
     */
    fun start(
        onComplete: (() -> Unit)? = null
    ) {
        onComplete?.invoke()
    }

    /**
     * Disabled legacy overlay.
     */
    fun startFirstTime(
        onComplete: (() -> Unit)? = null
    ) {
        onComplete?.invoke()
    }

    fun resetCompletion() {
        // No-op while the legacy tutorial is disabled.
    }

    fun dismiss() {
        // No overlay exists.
    }
}
