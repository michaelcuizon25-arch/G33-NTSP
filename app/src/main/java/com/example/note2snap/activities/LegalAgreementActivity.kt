package com.example.note2snap.activities

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.airbnb.lottie.LottieAnimationView
import com.example.note2snap.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class LegalAgreementActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "Note2SnapLegal"
        const val KEY_ACCEPTED = "LEGAL_V1_ACCEPTED"
    }

    private lateinit var cbTerms: CheckBox
    private lateinit var cbPermission: CheckBox
    private lateinit var btnContinue: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACCEPTED, false)
        ) {
            openMain()
            return
        }

        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_background
                )
            )
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
        }

        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(TextView(this).apply {
            text = "Before you continue"
            textSize = 30f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.apple_garamond_bold
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
        })

        content.addView(TextView(this).apply {
            text = "A quick agreement to help protect your notes, classmates, and instructors."
            textSize = 12f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text_secondary
                )
            )
        }, margin(top = 5, bottom = 14))

        val hero = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_surface_blue_soft
                )
            )
            strokeColor = ContextCompat.getColor(
                this@LegalAgreementActivity,
                R.color.nts_blue_line
            )
            strokeWidth = dp(1)
        }

        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(10), dp(15), dp(10))
        }

        heroRow.addView(
            LottieAnimationView(this).apply {
                setAnimation(R.raw.robot_mascot)
                repeatCount = -1
                playAnimation()
            },
            LinearLayout.LayoutParams(dp(82), dp(82))
        )

        heroRow.addView(
            TextView(this).apply {
                text = "Your scans may contain names, grades, class discussions, or instructor materials. Only capture content you are allowed to access."
                textSize = 11f
                typeface = ResourcesCompat.getFont(
                    this@LegalAgreementActivity,
                    R.font.poppins_medium
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@LegalAgreementActivity,
                        R.color.nts_text
                    )
                )
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        hero.addView(heroRow)
        content.addView(hero, margin(bottom = 16))

        content.addView(sectionTitle("Privacy at a glance"))

        content.addView(infoCard(
            "Local-first notes",
            "Notes and scan information are kept under the user's control in the app's local storage unless the user intentionally exports or shares them."
        ))

        content.addView(infoCard(
            "Minimal access",
            "Camera and gallery access are used only for capturing or importing images needed by Note2Snap."
        ))

        content.addView(infoCard(
            "You control your content",
            "Users can review, edit, save, organize, export, and delete their own notes."
        ))

        content.addView(infoCard(
            "Protect other people's information",
            "Do not scan private, confidential, restricted, or personally identifiable information without permission."
        ))

        content.addView(
            TextView(this).apply {
                text = "Read before agreeing"
                textSize = 14f
                typeface = ResourcesCompat.getFont(
                    this@LegalAgreementActivity,
                    R.font.poppins_semibold
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@LegalAgreementActivity,
                        R.color.nts_text
                    )
                )
            },
            margin(top = 10, bottom = 8)
        )

        content.addView(linkCard(
            "Terms of Use & User Agreement",
            "Rules for using Note2Snap responsibly."
        ) {
            openDocument(LegalDocumentActivity.DOC_TERMS)
        })

        content.addView(linkCard(
            "Privacy & Data Protection Notice",
            "How Note2Snap handles and protects information."
        ) {
            openDocument(LegalDocumentActivity.DOC_PRIVACY)
        })

        cbTerms = CheckBox(this).apply {
            text = "I have read and agree to the Terms of Use and Privacy Notice."
            textSize = 11f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
            buttonTintList = ContextCompat.getColorStateList(
                this@LegalAgreementActivity,
                R.color.nts_blue
            )
            setOnCheckedChangeListener { _, _ ->
                updateContinueState()
            }
        }
        content.addView(cbTerms, margin(top = 14))

        cbPermission = CheckBox(this).apply {
            text = "I understand that I must have permission before capturing or sharing content belonging to classmates, instructors, or the school."
            textSize = 11f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
            buttonTintList = ContextCompat.getColorStateList(
                this@LegalAgreementActivity,
                R.color.nts_blue
            )
            setOnCheckedChangeListener { _, _ ->
                updateContinueState()
            }
        }
        content.addView(cbPermission, margin(top = 4))

        btnContinue = MaterialButton(this).apply {
            text = "Agree & Continue"
            isAllCaps = false
            textSize = 13f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_semibold
            )
            cornerRadius = dp(18)
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_surface
                )
            )
            backgroundTintList = ContextCompat.getColorStateList(
                this@LegalAgreementActivity,
                R.color.nts_blue
            )
            isEnabled = false
            alpha = 0.45f
            setOnClickListener {
                acceptAndContinue()
            }
        }

        content.addView(
            btnContinue,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply {
                topMargin = dp(18)
            }
        )

        content.addView(TextView(this).apply {
            text = "You can review these documents anytime in Settings → Legal & Privacy."
            textSize = 9.5f
            gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text_secondary
                )
            )
        }, margin(top = 10))

        return scroll
    }

    private fun updateContinueState() {
        val enabled = cbTerms.isChecked && cbPermission.isChecked
        btnContinue.isEnabled = enabled
        btnContinue.alpha = if (enabled) 1f else 0.45f
    }

    private fun acceptAndContinue() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ACCEPTED, true)
            putLong("LEGAL_V1_ACCEPTED_AT", System.currentTimeMillis())
        }
        openMain()
    }

    private fun openMain() {
        startActivity(
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
        finish()
    }

    private fun openDocument(type: String) {
        startActivity(
            Intent(
                this,
                LegalDocumentActivity::class.java
            ).putExtra(
                LegalDocumentActivity.EXTRA_DOCUMENT,
                type
            )
        )
    }

    private fun sectionTitle(textValue: String): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_semibold
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
        }

    private fun infoCard(
        titleValue: String,
        bodyValue: String
    ): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_surface
                )
            )
            strokeColor = ContextCompat.getColor(
                this@LegalAgreementActivity,
                R.color.nts_blue_line
            )
            strokeWidth = dp(1)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        box.addView(TextView(this).apply {
            text = titleValue
            textSize = 11.5f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_semibold
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
        })

        box.addView(TextView(this).apply {
            text = bodyValue
            textSize = 9.8f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text_secondary
                )
            )
        }, margin(top = 3))

        card.addView(box)
        return card.apply {
            layoutParams = margin(top = 8)
        }
    }

    private fun linkCard(
        titleValue: String,
        bodyValue: String,
        action: () -> Unit
    ): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_surface_blue
                )
            )
            strokeColor = ContextCompat.getColor(
                this@LegalAgreementActivity,
                R.color.nts_blue_line
            )
            strokeWidth = dp(1)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(14), dp(12))
        }

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        labels.addView(TextView(this).apply {
            text = titleValue
            textSize = 11.5f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_semibold
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text
                )
            )
        })

        labels.addView(TextView(this).apply {
            text = bodyValue
            textSize = 9.5f
            typeface = ResourcesCompat.getFont(
                this@LegalAgreementActivity,
                R.font.poppins_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_text_secondary
                )
            )
        }, margin(top = 2))

        row.addView(
            labels,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(
                ContextCompat.getColor(
                    this@LegalAgreementActivity,
                    R.color.nts_blue
                )
            )
        })

        card.addView(row)
        return card.apply {
            layoutParams = margin(top = 8)
        }
    }

    private fun margin(
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
