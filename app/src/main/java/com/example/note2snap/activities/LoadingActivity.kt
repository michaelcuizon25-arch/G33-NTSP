package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R

class LoadingActivity : AppCompatActivity() {

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val scheduled =
        mutableListOf<Runnable>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContentView(
            R.layout.activity_loading
        )

        val robot =
            findViewById<LottieAnimationView>(
                R.id.loadingRobot
            )

        val title =
            findViewById<TextView>(
                R.id.tvLoadingTitle
            )

        val subtitle =
            findViewById<TextView>(
                R.id.tvLoadingSubtitle
            )

        val progress =
            findViewById<ProgressBar>(
                R.id.loadingProgress
            )

        val progressLabel =
            findViewById<TextView>(
                R.id.tvLoadingProgressLabel
            )

        val steps =
            listOf(
                findViewById<TextView>(
                    R.id.tvStep1Check
                ),
                findViewById<TextView>(
                    R.id.tvStep2Check
                ),
                findViewById<TextView>(
                    R.id.tvStep3Check
                ),
                findViewById<TextView>(
                    R.id.tvStep4Check
                ),
                findViewById<TextView>(
                    R.id.tvStep5Check
                )
            )

        robot.playAnimation()

        val stageMessages =
            listOf(
                "Cleaning up the image",
                "Reading the whiteboard",
                "Finding diagrams and elements",
                "Organizing your note",
                "Finishing things up"
            )

        val progressValues =
            listOf(
                16,
                38,
                61,
                82,
                100
            )

        steps.forEachIndexed {
                index,
                checkView ->

            val runnable =
                Runnable {
                    steps.forEachIndexed {
                            stepIndex,
                            item ->

                        item.text =
                            when {
                                stepIndex < index ->
                                    "✓"
                                stepIndex == index ->
                                    "●"
                                else ->
                                    "○"
                            }
                    }

                    checkView.text =
                        if (
                            index == steps.lastIndex
                        ) {
                            "✓"
                        } else {
                            "●"
                        }

                    title.text =
                        if (
                            index < 3
                        ) {
                            "Turning your board into notes"
                        } else {
                            "Making it study-ready"
                        }

                    subtitle.text =
                        stageMessages[index]

                    progress.progress =
                        progressValues[index]

                    progressLabel.text =
                        "${progressValues[index]}%"
                }

            scheduled.add(
                runnable
            )

            handler.postDelayed(
                runnable,
                420L +
                    index * 520L
            )
        }

        val finishRunnable =
            Runnable {
                steps.forEach {
                    it.text =
                        "✓"
                }

                progress.progress =
                    100

                progressLabel.text =
                    "100%"

                subtitle.text =
                    "Ready!"

                startActivity(
                    Intent(
                        this,
                        SaveFolderActivity::class.java
                    )
                )

                finish()
            }

        scheduled.add(
            finishRunnable
        )

        handler.postDelayed(
            finishRunnable,
            3250L
        )
    }

    override fun onDestroy() {
        scheduled.forEach {
            handler.removeCallbacks(
                it
            )
        }

        scheduled.clear()

        super.onDestroy()
    }
}
