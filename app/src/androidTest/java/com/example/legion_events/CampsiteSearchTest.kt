package com.example.legion_events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class CampsiteSearchTest {
    @get:Rule val compose = createComposeRule()
    private val campsites = object : CampsiteRepository {
        override fun observeCampsites() = flowOf(listOf(
            Campsite("pine", "alice", "Pine Ridge", "Banff", 6, 15_000),
            Campsite("spruce", "bob", "Spruce Creek", "Jasper", 2, 6500),
        ))
    }

    @Test fun searchByAreaAndClearRestoresTheListings() {
        openSearch()
        compose.onNodeWithText("Search campsites").performTextInput("Jasper")
        compose.onNodeWithText("Search campsites").performImeAction()
        compose.onNodeWithText("Spruce Creek").assertIsDisplayed()
        compose.onNodeWithText("Pine Ridge").assertDoesNotExist()
        compose.onNodeWithText("Search campsites").performTextClearance()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
    }

    @Test fun guestAndBudgetFiltersChangeTheResults() {
        openSearch()
        compose.onNodeWithText("4+ guests").performClick()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
        compose.onNodeWithText("Spruce Creek").assertDoesNotExist()
        compose.onNodeWithText("$100 or less").performClick()
        compose.onNodeWithText("Spruce Creek").assertIsDisplayed()
        compose.onNodeWithText("Pine Ridge").assertDoesNotExist()
        compose.onNodeWithText("All sites").performClick()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
    }

    private fun openSearch() {
        lateinit var onboarding: OnboardingController
        compose.runOnUiThread {
            onboarding = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("alice")
            })
        }
        compose.setContent { LegioneventsTheme { AppNavigation(onboarding, campsites = campsites) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pine Ridge").fetchSemanticsNodes().isNotEmpty() }
    }
}
