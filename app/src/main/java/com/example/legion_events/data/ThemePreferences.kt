/*
 * ThemePreferences.kt
 *
 * Purpose: Stores the device's chosen light or dark appearance and text size.
 * Role: Data layer in MVC; used by ThemeController.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.TextSize
import android.content.SharedPreferences

/** Preference key for the device's selected appearance. */
private const val DARK_THEME = "dark_theme"
/** Preference key for the device's selected [TextSize], stored by name. */
private const val TEXT_SIZE = "text_size"

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

    /** Returns the saved text size, using [TextSize.DEFAULT] before a selection or for an unknown value. */
    fun textSize(): TextSize =
        runCatching { TextSize.valueOf(preferences.getString(TEXT_SIZE, null) ?: TextSize.DEFAULT.name) }.getOrDefault(TextSize.DEFAULT)

    /** Saves the text size choice without blocking the UI on a disk write. */
    fun setTextSize(size: TextSize) {
        preferences.edit().putString(TEXT_SIZE, size.name).apply()
    }
}
