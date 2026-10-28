package com.example.legion_events

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.ThemeController
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ThemePreferences
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.DarkForest
import com.example.legion_events.ui.theme.ForestGreen
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.example.legion_events.ui.theme.LightBackground
import com.example.legion_events.view.ThemeToggle
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ThemeSelectionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var preferences: SharedPreferences

    @Before fun setUp() {
        preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("appearance-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After fun tearDown() { preferences.edit().clear().commit() }

    @Test fun selectionUpdatesTheWholePalettePersistsAndKeepsSearchState() {
        val theme = ThemeController(ThemePreferences(preferences))
        lateinit var onboarding: OnboardingController
        lateinit var palette: ColorScheme
        var signIns = 0
        compose.runOnUiThread {
            onboarding = OnboardingController(object : UserRepository {
                override suspend fun initializeUser(): UserProfile { signIns++; return UserProfile("alice") }
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(listOf(Campsite("pine", "alice", "Pine Ridge", "Banff", 6, 6500)))
        }
        compose.setContent {
            val darkTheme by theme.darkTheme.collectAsStateWithLifecycle()
            LegioneventsTheme(darkTheme = darkTheme) {
                val colors = MaterialTheme.colorScheme
                SideEffect { palette = colors }
                Scaffold(topBar = { ThemeToggle(darkTheme, theme::setDarkTheme) }) { padding ->
                    Box(Modifier.padding(padding)) { AppNavigation(onboarding, campsites = campsites) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pine Ridge").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dark mode").assertIsOff()
        compose.runOnIdle {
            assertEquals(LightBackground, palette.background)
            assertEquals(ForestGreen, palette.primary)
            assertReadable(palette)
        }
        compose.onNodeWithText("Search campsites").performTextInput("Banff")
        compose.onNodeWithText("Search campsites").performImeAction()
        compose.onNodeWithText("Dark mode").performClick().assertIsOn()
        compose.onNodeWithText("Search campsites").assertTextContains("Banff")
        compose.runOnIdle {
            assertEquals(DarkForest, palette.background)
            assertReadable(palette)
            assertTrue(ThemeController(ThemePreferences(preferences)).darkTheme.value)
            assertEquals(1, signIns)
        }
        compose.onNodeWithText("Dark mode").performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(LightBackground, palette.background)
            assertFalse(ThemePreferences(preferences).isDarkTheme())
        }
    }

    /** Checks the main text/button color pairs against the normal-text contrast requirement. */
    private fun assertReadable(colors: ColorScheme) {
        for ((foreground, background) in listOf(colors.onSurface to colors.surface,
            colors.onBackground to colors.background, colors.onPrimary to colors.primary)) {
            val first = foreground.luminance()
            val second = background.luminance()
            assertTrue("Text contrast must be at least 4.5:1", (maxOf(first, second) + .05) / (minOf(first, second) + .05) >= 4.5)
        }
    }
}
