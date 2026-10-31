package com.example.legion_events

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.data.WelcomePreferences
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses fake repositories so profile checks never change real accounts. */
class ProfileNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val campsites = object : CampsiteRepository {
        override fun observeCampsites() = flowOf(emptyList<Campsite>())
    }
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("profile-navigation-test", Context.MODE_PRIVATE)
    private val profiles = FakeProfiles()
    private var accounts = 0
    private lateinit var navigation: NavHostController

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun welcomeIsShownOnceAndOpensPolicy() {
        open()
        compose.onNodeWithText("A fair chance to get outdoors.").assertIsDisplayed()
        compose.onNodeWithText("App policy & guidelines").performClick()
        compose.onNodeWithText("Fair lotteries").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Start exploring").performClick()
        waitFor("Explore campsites")
        compose.runOnIdle { assertNull(navigation.previousBackStackEntry) }

        compose.runOnIdle { assertEquals(true, WelcomePreferences(preferences).hasSeenWelcome()) }
    }

    @Test
    fun seenWelcomeGoesStraightHome() {
        WelcomePreferences(preferences).markWelcomeSeen()
        open()
        waitFor("Explore campsites")
        compose.onNodeWithText("Start exploring").assertDoesNotExist()
    }

    @Test
    fun editsAndSavesProfileFromBottomBar() {
        WelcomePreferences(preferences).markWelcomeSeen()
        open()
        waitFor("Explore campsites")
        compose.onNodeWithText("Profile").performClick()
        waitFor("Alex Morgan")
        compose.onNodeWithText("Alex Morgan").performTextReplacement("Sam Lee")
        compose.onNodeWithText("Phone").performTextReplacement("780 555 0100")
        compose.onNodeWithText("Save changes").performClick()
        waitFor("Profile saved.")
        compose.runOnIdle { assertEquals(Triple("Sam Lee", "alex@example.com", "780 555 0100"), profiles.saved) }

        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.runOnIdle { assertEquals(false, profiles.notifications) }
    }

    @Test
    fun invalidEmailShowsError() {
        WelcomePreferences(preferences).markWelcomeSeen()
        open()
        waitFor("Explore campsites")
        compose.onNodeWithText("Profile").performClick()
        waitFor("alex@example.com")
        compose.onNodeWithText("alex@example.com").performTextReplacement("alex")
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText("Enter a valid email address.").assertIsDisplayed()
        compose.runOnIdle { assertNull(profiles.saved) }
    }

    @Test
    fun deletingProfileOpensNewAccount() {
        WelcomePreferences(preferences).markWelcomeSeen()
        open()
        waitFor("Explore campsites")
        compose.onNodeWithText("Profile").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Delete profile").performScrollTo().performClick()
        compose.onNodeWithText("Delete your profile?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, profiles.deletes) }

        compose.onNodeWithText("Delete profile").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        waitFor("Explore campsites")
        compose.runOnIdle {
            assertEquals(1, profiles.deletes)
            assertEquals(2, accounts)
            assertNull(navigation.previousBackStackEntry)
        }
    }

    private fun open() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(object : UserRepository {
                override suspend fun initializeUser(): UserProfile {
                    accounts++
                    return UserProfile("entrant-$accounts")
                }
            })
        }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme {
                AppNavigation(controller, navigation, campsites, profiles = profiles, welcome = WelcomePreferences(preferences))
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Opening your account…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private class FakeProfiles : ProfileRepository {
        var saved: Triple<String, String, String>? = null
        var notifications: Boolean? = null
        var deletes = 0

        override suspend fun getProfile() = UserProfile("entrant", "Alex Morgan", "alex@example.com", "780-555-0142")

        override suspend fun updateContact(name: String, email: String, phone: String) {
            saved = Triple(name, email, phone)
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            notifications = enabled
        }

        override suspend fun deleteProfile() {
            deletes++
        }
    }
}
