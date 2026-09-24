package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.note2snap.R
import com.google.android.material.card.MaterialCardView
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var navHome: LinearLayout
    private lateinit var navNotes: LinearLayout
    private lateinit var navHistory: LinearLayout
    private lateinit var navSettings: LinearLayout
    private lateinit var scanFab: MaterialCardView

    private lateinit var iconHome: ImageView
    private lateinit var iconNotes: ImageView
    private lateinit var iconHistory: ImageView
    private lateinit var iconSettings: ImageView

    private lateinit var textHome: TextView
    private lateinit var textNotes: TextView
    private lateinit var textHistory: TextView
    private lateinit var textSettings: TextView
    private lateinit var bottomNavContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {

        val sharedPref =
            getSharedPreferences(
                "AppSettings",
                Context.MODE_PRIVATE
            )

        val isSystemDark =
            (
                    resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK
                    ) ==
                    Configuration.UI_MODE_NIGHT_YES

        val isDarkModeSaved =
            sharedPref.getBoolean(
                "DARK_MODE",
                isSystemDark
            )

        val targetMode =
            if (isDarkModeSaved) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }

        if (
            AppCompatDelegate.getDefaultNightMode() !=
            targetMode
        ) {
            AppCompatDelegate.setDefaultNightMode(
                targetMode
            )
        }

        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        bottomNavContainer =
            findViewById(
                R.id.bottomNavContainer
            )

        navHome =
            findViewById(
                R.id.navHome
            )

        navNotes =
            findViewById(
                R.id.navNotes
            )

        navHistory =
            findViewById(
                R.id.navHistory
            )

        navSettings =
            findViewById(
                R.id.navSettings
            )

        scanFab =
            findViewById(
                R.id.scanFab
            )

        iconHome =
            findViewById(
                R.id.iconHome
            )

        iconNotes =
            findViewById(
                R.id.iconNotes
            )

        iconHistory =
            findViewById(
                R.id.iconHistory
            )

        iconSettings =
            findViewById(
                R.id.iconSettings
            )

        textHome =
            findViewById(
                R.id.textHome
            )

        textNotes =
            findViewById(
                R.id.textNotes
            )

        textHistory =
            findViewById(
                R.id.textHistory
            )

        textSettings =
            findViewById(
                R.id.textSettings
            )

        if (savedInstanceState == null) {

            loadFragment(
                HomeFragment()
            )

            setActiveTab(
                "home"
            )
        }

        navHome.setOnClickListener {

            loadFragment(
                HomeFragment()
            )

            setActiveTab(
                "home"
            )
        }

        navNotes.setOnClickListener {

            loadFragment(
                NotesFragment()
            )

            setActiveTab(
                "notes"
            )
        }

        navHistory.setOnClickListener {

            loadFragment(
                HistoryFragment()
            )

            setActiveTab(
                "history"
            )
        }

        navSettings.setOnClickListener {

            loadFragment(
                SettingsFragment()
            )

            setActiveTab(
                "settings"
            )
        }

        scanFab.setOnClickListener {

            loadFragment(
                ScanFragment()
            )

            setActiveTab(
                "scan"
            )
        }
    }

    fun loadFragment(
        fragment: Fragment
    ) {

        val isScanScreen =
            fragment is ScanFragment

        bottomNavContainer.visibility =
            if (isScanScreen) {
                View.GONE
            } else {
                View.VISIBLE
            }

        scanFab.visibility =
            if (isScanScreen) {
                View.GONE
            } else {
                View.VISIBLE
            }

        supportFragmentManager
            .beginTransaction()
            .replace(
                R.id.fragmentContainer,
                fragment
            )
            .commit()
    }

    private fun setActiveTab(
        activeTab: String
    ) {

        val activeColor =
            ContextCompat.getColor(
                this,
                R.color.primary
            )

        val inactiveColor =
            ContextCompat.getColor(
                this,
                R.color.text_secondary
            )

        iconHome.setColorFilter(
            if (activeTab == "home")
                activeColor
            else
                inactiveColor
        )

        textHome.setTextColor(
            if (activeTab == "home")
                activeColor
            else
                inactiveColor
        )

        iconNotes.setColorFilter(
            if (activeTab == "notes")
                activeColor
            else
                inactiveColor
        )

        textNotes.setTextColor(
            if (activeTab == "notes")
                activeColor
            else
                inactiveColor
        )

        iconHistory.setColorFilter(
            if (activeTab == "history")
                activeColor
            else
                inactiveColor
        )

        textHistory.setTextColor(
            if (activeTab == "history")
                activeColor
            else
                inactiveColor
        )

        iconSettings.setColorFilter(
            if (activeTab == "settings")
                activeColor
            else
                inactiveColor
        )

        textSettings.setTextColor(
            if (activeTab == "settings")
                activeColor
            else
                inactiveColor
        )
    }

    fun selectTab(
        itemId: Int
    ) {

        when (itemId) {

            R.id.nav_home -> {

                loadFragment(
                    HomeFragment()
                )

                setActiveTab(
                    "home"
                )
            }

            R.id.nav_notes -> {

                loadFragment(
                    NotesFragment()
                )

                setActiveTab(
                    "notes"
                )
            }

            R.id.nav_history -> {

                loadFragment(
                    HistoryFragment()
                )

                setActiveTab(
                    "history"
                )
            }

            R.id.nav_settings -> {

                loadFragment(
                    SettingsFragment()
                )

                setActiveTab(
                    "settings"
                )
            }
        }
    }

    fun openScan() {

        loadFragment(
            ScanFragment()
        )

        setActiveTab(
            "scan"
        )
    }
}