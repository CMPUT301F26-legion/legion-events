/*
 * TextSize.kt
 *
 * Purpose: The three app-wide text size choices.
 * Role: Model in MVC; ThemeController holds the choice and LegioneventsTheme applies its scale.
 * Outstanding issues: Button and touch-target size are not scaled.
 */
package com.example.legion_events.model

/**
 * A text size choice, applied on top of the device's own font size setting.
 * @property scale Multiplier for every text size in the app.
 * @property label Accessible name of the choice.
 */
enum class TextSize(val scale: Float, val label: String) {
    SMALL(0.85f, "Small text"),
    DEFAULT(1f, "Default text"),
    LARGE(1.3f, "Large text"),
}
