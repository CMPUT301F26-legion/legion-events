package com.example.legion_events.data

import android.content.SharedPreferences

/** Preference key recording that the welcome screen was dismissed on this device. */
private const val WELCOME_SEEN = "welcome_seen"

/**
 * Remembers whether this device has already seen the welcome screen.
 * @property preferences Private app preferences supplied by the activity.
 */
class WelcomePreferences(private val preferences: SharedPreferences) {
    /** Returns true after the user has tapped Start exploring once. */
    fun hasSeenWelcome(): Boolean = preferences.getBoolean(WELCOME_SEEN, false)

    /** Saves the choice without blocking the UI on a disk write. */
    fun markWelcomeSeen() {
        preferences.edit().putBoolean(WELCOME_SEEN, true).apply()
    }
}
