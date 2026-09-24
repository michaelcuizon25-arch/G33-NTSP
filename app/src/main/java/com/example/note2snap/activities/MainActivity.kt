package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.note2snap.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: CurvedBottomNavigationView
    private lateinit var fabScan: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        // Read saved preference and set Night Mode BEFORE layout inflation
        val sharedPref = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkModeSaved = sharedPref.getBoolean("DARK_MODE", isSystemDark)

        val targetMode = if (isDarkModeSaved) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }

        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        bottomNav = findViewById(R.id.bottomNavigation)
        fabScan = findViewById(R.id.fabScan)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
            // Position initial curve over Home tab after layout renders
            bottomNav.post {
                bottomNav.animateCurveToItem(R.id.nav_home)
            }
        }

        // Handles tab item clicks and triggers sliding curve wave animation
        bottomNav.setOnItemSelectedListener { item ->
            bottomNav.animateCurveToItem(item.itemId)

            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_notes -> {
                    loadFragment(NotesFragment())
                    true
                }
                R.id.nav_history -> {
                    loadFragment(HistoryFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }

        // Tap camera button to immediately open ScanFragment
        fabScan.setOnClickListener {
            openScan()
        }
    }

    // Opens ScanFragment and aligns the curved bottom nav position
    fun openScan() {
        loadFragment(ScanFragment())
        bottomNav.selectedItemId = R.id.nav_placeholder
        bottomNav.animateCurveToItem(R.id.nav_placeholder)
    }

    fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // Call this to change active tab icon and animate wave curve
    fun selectTab(itemId: Int) {
        bottomNav.selectedItemId = itemId
        bottomNav.animateCurveToItem(itemId)
    }
}