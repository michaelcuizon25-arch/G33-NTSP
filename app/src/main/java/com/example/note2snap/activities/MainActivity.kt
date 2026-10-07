package com.example.note2snap.activities

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.note2snap.R
import com.google.android.material.card.MaterialCardView

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
        // Apply the Appearance setting before inflating any UI.
        // Do not read the old AppSettings/DARK_MODE preference here.
        ThemeManager.applySavedTheme(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()

        if (savedInstanceState == null) {
            selectTab(R.id.navHome)
        }
    }

    private fun initViews() {
        bottomNavContainer =
            findViewById(R.id.bottomNavContainer)

        navHome =
            findViewById(R.id.navHome)

        navNotes =
            findViewById(R.id.navNotes)

        navHistory =
            findViewById(R.id.navHistory)

        navSettings =
            findViewById(R.id.navSettings)

        scanFab =
            findViewById(R.id.scanFab)

        iconHome =
            findViewById(R.id.iconHome)

        iconNotes =
            findViewById(R.id.iconNotes)

        iconHistory =
            findViewById(R.id.iconHistory)

        iconSettings =
            findViewById(R.id.iconSettings)

        textHome =
            findViewById(R.id.textHome)

        textNotes =
            findViewById(R.id.textNotes)

        textHistory =
            findViewById(R.id.textHistory)

        textSettings =
            findViewById(R.id.textSettings)
    }

    private fun setupClickListeners() {
        navHome.setOnClickListener {
            selectTab(R.id.navHome)
        }

        navNotes.setOnClickListener {
            selectTab(R.id.navNotes)
        }

        navHistory.setOnClickListener {
            selectTab(R.id.navHistory)
        }

        navSettings.setOnClickListener {
            selectTab(R.id.navSettings)
        }

        scanFab.setOnClickListener {
            openScan()
        }
    }

    fun selectTab(itemId: Int) {
        bottomNavContainer.visibility =
            View.VISIBLE

        scanFab.visibility =
            View.VISIBLE

        val fragment: Fragment =
            when (itemId) {
                R.id.navHome,
                R.id.nav_home ->
                    HomeFragment()

                R.id.navNotes,
                R.id.nav_notes ->
                    NotesFragment()

                R.id.navHistory,
                R.id.nav_history ->
                    HistoryFragment()

                R.id.navSettings,
                R.id.nav_settings ->
                    SettingsFragment()

                else ->
                    HomeFragment()
            }

        updateNavUI(itemId)

        supportFragmentManager
            .beginTransaction()
            .replace(
                R.id.fragmentContainer,
                fragment
            )
            .commit()
    }

    fun openScan() {
        bottomNavContainer.visibility =
            View.GONE

        scanFab.visibility =
            View.GONE

        supportFragmentManager
            .beginTransaction()
            .replace(
                R.id.fragmentContainer,
                ScanFragment()
            )
            .commit()
    }

    private fun updateNavUI(
        selectedId: Int
    ) {
        val activeColor =
            ContextCompat.getColor(
                this,
                R.color.nav_active
            )

        val inactiveColor =
            ContextCompat.getColor(
                this,
                R.color.nav_inactive
            )

        resetTabUI(
            navHome,
            iconHome,
            textHome,
            inactiveColor
        )

        resetTabUI(
            navNotes,
            iconNotes,
            textNotes,
            inactiveColor
        )

        resetTabUI(
            navHistory,
            iconHistory,
            textHistory,
            inactiveColor
        )

        resetTabUI(
            navSettings,
            iconSettings,
            textSettings,
            inactiveColor
        )

        when (selectedId) {
            R.id.navHome,
            R.id.nav_home ->
                setTabActive(
                    navHome,
                    iconHome,
                    textHome,
                    activeColor
                )

            R.id.navNotes,
            R.id.nav_notes ->
                setTabActive(
                    navNotes,
                    iconNotes,
                    textNotes,
                    activeColor
                )

            R.id.navHistory,
            R.id.nav_history ->
                setTabActive(
                    navHistory,
                    iconHistory,
                    textHistory,
                    activeColor
                )

            R.id.navSettings,
            R.id.nav_settings ->
                setTabActive(
                    navSettings,
                    iconSettings,
                    textSettings,
                    activeColor
                )
        }
    }

    private fun resetTabUI(
        container: LinearLayout,
        icon: ImageView,
        text: TextView,
        color: Int
    ) {
        container.setBackgroundResource(
            R.drawable.bg_nav_item_inactive
        )

        icon.setColorFilter(color)
        text.setTextColor(color)
    }

    private fun setTabActive(
        container: LinearLayout,
        icon: ImageView,
        text: TextView,
        color: Int
    ) {
        container.setBackgroundResource(
            R.drawable.bg_nav_item_active
        )

        icon.setColorFilter(color)
        text.setTextColor(color)
    }
}