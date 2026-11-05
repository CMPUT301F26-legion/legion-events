/*
 * WelcomePreferences.kt
 *
 * Purpose: Remembers on this device whether the S01 welcome screen has been shown.
 * Role: Data layer (local device storage), used by AppNavigation.
 * Outstanding issues: The flag is per install, not per account, so deleting a profile does not
 *   show S01 again.
 */
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
