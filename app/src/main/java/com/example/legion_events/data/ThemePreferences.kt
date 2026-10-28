package com.example.legion_events.data

import android.content.SharedPreferences

/** Preference key for the device's selected appearance. */
private const val DARK_THEME = "dark_theme"

/**
 * Stores the appearance choice locally on this device.
 * @property preferences Private app preferences supplied by the activity.
 */
class ThemePreferences(private val preferences: SharedPreferences) {
    /** Returns the saved choice, using light mode before the user makes a selection. */
    fun isDarkTheme(): Boolean = preferences.getBoolean(DARK_THEME, false)

    /** Saves the user's choice without blocking the UI on a disk write. */
    fun setDarkTheme(enabled: Boolean) {
        preferences.edit().putBoolean(DARK_THEME, enabled).apply()
    }
}
