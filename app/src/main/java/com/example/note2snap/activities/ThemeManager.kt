package com.example.note2snap.activities

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    fun applySavedTheme(context: Context) {
        val mode =
            context
                .getSharedPreferences(
                    SettingsFragment.THEME_PREFS,
                    Context.MODE_PRIVATE
                )
                .getInt(
                    SettingsFragment.KEY_THEME_MODE,
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )

        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
