/*
 * AdminNavigationTest.kt
 *
 * Purpose: Intent tests for the S19 admin dashboard: browse, search, and remove campsites and
 *   profiles (US 03.04.01, 03.01.01, 03.05.01, 03.02.01, 03.07.01).
 * Role: Drives the real router and screens with an in-memory AdminRepository; no Firebase data changes.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ModeratedCampsite
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Walks the S19 Campsites and Profiles tabs as an admin, with fake data. */
class AdminNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigation: NavHostController

    private val admin = object : AdminRepository {
        var campsites = listOf(
            ModeratedCampsite(Campsite("lake", "jane", "Two Jack Lakeside", "Banff", 6, 3800), true),
            ModeratedCampsite(Campsite("pine", "jane", "Pine Ridge", "Jasper", 4, 2900), true),
        )
        var profiles = listOf(
            UserProfile("jane", name = "Jane Doe", email = "jane@example.com"),
            UserProfile("jill", name = "Jill Poe"),
            UserProfile("admin", name = "Admin Ann", role = "admin"),
        )

        override suspend fun getCampsites() = campsites
        override suspend fun getCampsite(campsiteId: String) = campsites.firstOrNull { it.campsite.id == campsiteId }
        override suspend fun getProfiles() = profiles
        override suspend fun getProfile(userId: String) = profiles.firstOrNull { it.uid == userId }

        override suspend fun removeCampsite(campsiteId: String) {
            campsites = campsites.map { if (it.campsite.id == campsiteId) it.copy(active = false) else it }
        }

        override suspend fun removeProfile(userId: String) {
            profiles = profiles.map { if (it.uid == userId) it.copy(disabled = true) else it }
        }

        override suspend fun removeOwner(userId: String): Int {
            removeProfile(userId)
            val owned = campsites.filter { it.active && it.campsite.ownerId == userId }
            owned.forEach { removeCampsite(it.campsite.id) }
            return owned.size
        }
    }

    /** Browse and search campsites, open one, and remove it after confirming (US 03.04.01, 03.01.01). */
    @Test
    fun browseSearchAndRemoveCampsite() {
        open()
        waitFor("Campsite listings")
        compose.onNodeWithText("Two Jack Lakeside").assertIsDisplayed()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
        compose.onAllNodesWithText("Owner: Jane Doe")[0].assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextInput("jasper")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Two Jack Lakeside").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Pine Ridge").performClick()

        waitFor("Remove listing")
        compose.onNodeWithText("Remove listing").performScrollTo().performClick()
        waitFor("Remove this listing?")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(admin.campsites.first { it.campsite.id == "pine" }.active) }

        compose.onNodeWithText("Remove listing").performScrollTo().performClick()
        waitFor("Remove this listing?")
        compose.onNodeWithText("Remove").performClick()
        waitFor("This listing was removed. Entrants can no longer find or open it.")
        compose.runOnIdle { assertFalse(admin.campsites.first { it.campsite.id == "pine" }.active) }
    }

    /** Browse profiles and remove an owner with their campsites, then a profile (US 03.05.01, 03.07.01, 03.02.01). */
    @Test
    fun browseProfilesAndRemoveOwnerAndProfile() {
        open()
        waitFor("Campsite listings")
        compose.onNodeWithText("Profiles").performClick()
        waitFor("Jane Doe")
        compose.onNodeWithText("Owner").assertIsDisplayed()
        compose.onNodeWithText("Admin").assertIsDisplayed()

        compose.onNodeWithText("Remove owner").performClick()
        waitFor("Remove Jane Doe as an owner?")
        compose.onNodeWithText("Remove").performClick()
        waitFor("Removed Jane Doe and 2 campsites.")
        compose.runOnIdle {
            assertTrue(admin.profiles.first { it.uid == "jane" }.disabled)
            assertTrue(admin.campsites.none { it.active })
        }

        compose.onNodeWithText("Remove profile").performClick()
        waitFor("Remove Jill Poe?")
        compose.onNodeWithText("Remove").performClick()
        waitFor("Removed Jill Poe.")
        compose.runOnIdle { assertTrue(admin.profiles.first { it.uid == "jill" }.disabled) }
        // Only the admin account is left active, and it has no remove buttons.
        compose.onNodeWithText("Remove profile").assertDoesNotExist()
    }

    /** Opens the app as an admin, already on S19. */
    private fun open() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("admin", role = "admin")
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(emptyList<Campsite>())
        }
        val details = object : CampsiteDetailsRepository {
            override suspend fun getCampsite(campsiteId: String) = null
            override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?) = emptyList<Photo>()
        }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme {
                AppNavigation(controller, navigation, campsites, details = details, admin = admin)
            }
        }
        waitFor("Explore campsites")
        compose.runOnIdle { navigation.navigate("admin") }
        compose.runOnIdle { assertEquals("admin", navigation.currentDestination?.route) }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text)[0].assertIsDisplayed()
    }
}
