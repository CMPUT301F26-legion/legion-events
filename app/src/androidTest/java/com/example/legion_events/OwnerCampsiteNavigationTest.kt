/*
 * OwnerCampsiteNavigationTest.kt
 *
 * Purpose: Intent tests for the owner campsite screens S12 to S14 (US 02.01.01 to 02.03.01).
 * Role: Drives the real router with a fake OwnerCampsiteRepository.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Walks the owner screens (S12 to S14) with a fake repository so no real listings change. */
class OwnerCampsiteNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private var stored = Campsite(
        id = "lake", ownerId = "owner", name = "Two Jack Lakeside", locationName = "Banff National Park, AB",
        maxGuests = 6, pricePerNight = 3800, location = GeoPoint(51.2207, -115.4927), rules = listOf("Quiet after 11 pm"),
        cancellationPolicy = "No refunds", availableNights = listOf("2027-06-01", "2027-06-02"),
    )
    private val saves = mutableListOf<Campsite>()
    private lateinit var navigation: NavHostController

    private val owners = object : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = listOf(stored)
        override suspend fun getMyCampsite(campsiteId: String) = stored.takeIf { it.id == campsiteId }
        override suspend fun getMyLotteries() = emptyList<Lottery>()
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String {
            saves += campsite
            stored = campsite.copy(ownerId = stored.ownerId)
            return campsite.id
        }
    }

    @Test
    fun dashboardOpensDetailsAndEditSavesBackToDetails() {
        open()
        compose.runOnIdle { navigation.navigate("owner") }
        waitFor("My Campsites")
        compose.onNodeWithText("No lottery").assertIsDisplayed()
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Two Jack Lakeside").performClick()
        waitFor("Max occupancy")
        compose.onNodeWithText("Jun 1 to Jun 2").assertIsDisplayed()

        compose.onNodeWithText("✎ Edit").performClick()
        waitFor("Edit Campsite")
        compose.onNodeWithText("Two Jack Lakeside").performTextReplacement("Two Jack Main")
        compose.onNodeWithText("Cancel").performScrollTo()
        compose.onAllNodesWithText("Save")[1].performClick()
        waitFor("Two Jack Main")
        compose.onNodeWithText("Max occupancy").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Two Jack Main", saves.single().name)
            assertEquals("owner/campsite/{campsiteId}", navigation.currentDestination?.route)
        }

        // Scroll to the button itself: on small screens it sits below the card title, off-screen.
        compose.onNodeWithText("Generate QR").performScrollTo().performClick()
        waitFor("Generated")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("QR code for Two Jack Main").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addCampsiteShowsInlineErrorsForMissingFields() {
        open()
        compose.runOnIdle { navigation.navigate("owner") }
        waitFor("My Campsites")
        compose.onNodeWithText("Add Campsite", useUnmergedTree = true).performClick()
        waitFor("Basic info")
        compose.onAllNodesWithText("Save")[0].performClick()
        waitFor("Enter a campsite name.")
        compose.onNodeWithText("Not saved yet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Enter the area or town.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves.size) }

        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        waitFor("My Campsites")
    }

    private fun open() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("owner")
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(emptyList<Campsite>())
        }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme { AppNavigation(controller, navigation, campsites, owners = owners) }
        }
        waitFor("Explore campsites")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
