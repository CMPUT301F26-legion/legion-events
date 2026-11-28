/*
 * ThemeControllerTest.kt
 *
 * Purpose: Verify the text size choice is restored, saved once per change, and defaults safely.
 * Role: Local JVM tests for ThemeController and ThemePreferences with mocked SharedPreferences.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import android.content.SharedPreferences
import com.example.legion_events.data.ThemePreferences
import com.example.legion_events.model.TextSize
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Uses an in-memory string store behind a mocked SharedPreferences. */
class ThemeControllerTest {
    private val stored = mutableMapOf<String, String?>()
    private val editor = mock(SharedPreferences.Editor::class.java)
    private val preferences = mock(SharedPreferences::class.java).also { prefs ->
        `when`(prefs.getString(anyString(), any())).thenAnswer { stored[it.getArgument(0)] ?: it.getArgument(1) }
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), any())).thenAnswer { stored[it.getArgument(0)] = it.getArgument(1); editor }
    }

    @Test fun textSizeDefaultsThenSavesAndRestoresTheChoice() {
        val controller = ThemeController(ThemePreferences(preferences))
        assertEquals(TextSize.DEFAULT, controller.textSize.value)
        controller.setTextSize(TextSize.LARGE)
        assertEquals(TextSize.LARGE, controller.textSize.value)
        assertEquals(TextSize.LARGE, ThemeController(ThemePreferences(preferences)).textSize.value)
        controller.setTextSize(TextSize.LARGE)
        verify(editor, times(1)).putString("text_size", "LARGE")
    }

    @Test fun anUnknownSavedValueFallsBackToDefault() {
        stored["text_size"] = "HUGE"
        assertEquals(TextSize.DEFAULT, ThemePreferences(preferences).textSize())
    }

    @Test fun scalesAreOrderedSmallToLarge() {
        assertEquals(listOf(0.85f, 1f, 1.3f), TextSize.entries.map { it.scale })
    }
}
