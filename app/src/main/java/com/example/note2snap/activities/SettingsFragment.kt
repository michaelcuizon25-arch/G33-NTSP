package com.example.note2snap.activities

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.example.note2snap.R
import com.google.android.material.bottomsheet.BottomSheetDialog

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshAppearanceLabel(view)

        view.findViewById<View>(R.id.cardAppearance).setOnClickListener {
            showAppearanceSheet(view)
        }

        view.findViewById<View>(R.id.cardStorage).setOnClickListener {
            openSettingsPage(StorageFragment())
        }

        view.findViewById<View>(R.id.cardAbout).setOnClickListener {
            openSettingsPage(AboutFragment())
        }

        view.findViewById<View>(R.id.cardTerms).setOnClickListener {
            startActivity(
                Intent(requireContext(), LegalDocumentActivity::class.java)
                    .putExtra(
                        LegalDocumentActivity.EXTRA_DOCUMENT,
                        LegalDocumentActivity.DOC_TERMS
                    )
            )
        }

        view.findViewById<View>(R.id.cardPrivacy).setOnClickListener {
            startActivity(
                Intent(requireContext(), LegalDocumentActivity::class.java)
                    .putExtra(
                        LegalDocumentActivity.EXTRA_DOCUMENT,
                        LegalDocumentActivity.DOC_PRIVACY
                    )
            )
        }
    }

    private fun openSettingsPage(fragment: Fragment) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun getSavedThemeMode(): Int {
        return requireContext()
            .getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .getInt(
                KEY_THEME_MODE,
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
    }

    private fun refreshAppearanceLabel(root: View) {
        root.findViewById<TextView>(R.id.tvAppearanceValue).text =
            when (getSavedThemeMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
                AppCompatDelegate.MODE_NIGHT_NO -> "Light"
                else -> "System default"
            }
    }

    private fun showAppearanceSheet(settingsRoot: View) {
        val dialog = BottomSheetDialog(requireContext())
        val currentMode = getSavedThemeMode()

        val sheet = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(28))
            background = roundedBackground(
                colorRes(R.color.nts_background),
                28f
            )
        }

        sheet.addView(
            View(requireContext()).apply {
                background = roundedBackground(
                    colorRes(R.color.nts_blue_line),
                    4f
                )
            },
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        sheet.addView(TextView(requireContext()).apply {
            text = "Appearance"
            textSize = 20f
            typeface = ResourcesCompat.getFont(
                requireContext(),
                R.font.poppins_semibold
            )
            setTextColor(colorRes(R.color.nts_text))
        })

        sheet.addView(
            TextView(requireContext()).apply {
                text = "Choose how Note2Snap looks."
                textSize = 10.5f
                typeface = ResourcesCompat.getFont(
                    requireContext(),
                    R.font.poppins_regular
                )
                setTextColor(colorRes(R.color.nts_text_secondary))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(14)
            }
        )

        sheet.addView(
            appearanceOption(
                "System default",
                "Follow your phone's current theme",
                currentMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ) {
                saveTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                dialog.dismiss()
                refreshAppearanceLabel(settingsRoot)
            }
        )

        sheet.addView(
            appearanceOption(
                "Light",
                "Always use the light theme",
                currentMode == AppCompatDelegate.MODE_NIGHT_NO
            ) {
                saveTheme(AppCompatDelegate.MODE_NIGHT_NO)
                dialog.dismiss()
                refreshAppearanceLabel(settingsRoot)
            }
        )

        sheet.addView(
            appearanceOption(
                "Dark",
                "Always use the dark theme",
                currentMode == AppCompatDelegate.MODE_NIGHT_YES
            ) {
                saveTheme(AppCompatDelegate.MODE_NIGHT_YES)
                dialog.dismiss()
                refreshAppearanceLabel(settingsRoot)
            }
        )

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun appearanceOption(
        title: String,
        subtitle: String,
        selected: Boolean,
        action: () -> Unit
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(13), dp(14), dp(13))
            background = roundedBackground(
                if (selected) colorRes(R.color.nts_surface_blue)
                else colorRes(R.color.nts_surface),
                18f,
                if (selected) colorRes(R.color.nts_blue)
                else colorRes(R.color.nts_blue_line)
            )
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        }

        val labels = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        labels.addView(TextView(requireContext()).apply {
            text = title
            textSize = 13f
            typeface = ResourcesCompat.getFont(
                requireContext(),
                R.font.poppins_medium
            )
            setTextColor(colorRes(R.color.nts_text))
        })

        labels.addView(TextView(requireContext()).apply {
            text = subtitle
            textSize = 10f
            typeface = ResourcesCompat.getFont(
                requireContext(),
                R.font.poppins_regular
            )
            setTextColor(colorRes(R.color.nts_text_secondary))
        })

        row.addView(
            labels,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        if (selected) {
            row.addView(
                TextView(requireContext()).apply {
                    text = "✓"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    typeface = ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_semibold
                    )
                    setTextColor(colorRes(R.color.nts_blue))
                },
                LinearLayout.LayoutParams(dp(34), dp(34))
            )
        }

        return row
    }

    private fun saveTheme(mode: Int) {
        requireContext()
            .getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_THEME_MODE, mode)
            }

        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(color)
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun colorRes(id: Int): Int =
        ContextCompat.getColor(requireContext(), id)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val THEME_PREFS = "ThemeSettings"
        const val KEY_THEME_MODE = "THEME_MODE"
    }
}
