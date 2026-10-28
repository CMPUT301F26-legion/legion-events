package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import com.example.legion_events.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supplies one appearance setting to the whole app and handles theme changes.
 * @property preferences Stores the selection across app restarts.
 */
class ThemeController(private val preferences: ThemePreferences) : ViewModel() {
    /** Current appearance, changed only by this controller. */
    private val mutableDarkTheme = MutableStateFlow(preferences.isDarkTheme())
    /** Read-only appearance state consumed by the app's root theme. */
    val darkTheme = mutableDarkTheme.asStateFlow()

    /** Updates every themed component and remembers the selection for the next launch. */
    fun setDarkTheme(enabled: Boolean) {
        if (enabled == darkTheme.value) return
        preferences.setDarkTheme(enabled)
        mutableDarkTheme.value = enabled
    }
}
