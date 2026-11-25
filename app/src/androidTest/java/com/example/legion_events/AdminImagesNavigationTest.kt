/*
 * AdminImagesNavigationTest.kt
 *
 * Purpose: Intent tests for the S19 Images tab: browse every uploaded image and remove one after
 *   confirming (US 03.06.01, 03.03.01).
 * Role: Drives the real router and screens with in-memory repositories; no Firebase data changes.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.AdminImageRepository
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
import org.junit.Rule
import org.junit.Test

/** Walks the S19 Images tab as an admin, with fake data. */
class AdminImagesNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigation: NavHostController

    private val admin = object : AdminRepository {
        val campsites = listOf(
            ModeratedCampsite(Campsite("lake", "jane", "Two Jack Lakeside", "Banff", 6, 3800, photoId = "p1"), true),
            ModeratedCampsite(Campsite("pine", "jill", "Pine Ridge", "Jasper", 4, 2900), true),
        )
        val profiles = listOf(UserProfile("jane", name = "Jane Doe"), UserProfile("jill", name = "Jill Poe"))

        override suspend fun getCampsites() = campsites
        override suspend fun getCampsite(campsiteId: String) = campsites.firstOrNull { it.campsite.id == campsiteId }
        override suspend fun getProfiles() = profiles
        override suspend fun getProfile(userId: String) = profiles.firstOrNull { it.uid == userId }
        override suspend fun removeCampsite(campsiteId: String) = Unit
        override suspend fun removeProfile(userId: String) = Unit
        override suspend fun removeOwner(userId: String) = 0
    }

    private val images = object : AdminImageRepository {
        var photos = listOf(
            Photo("p1", "jane", "campsite", "lake", "", "image/jpeg"),
            Photo("p2", "jill", "campsite", "pine", "", "image/jpeg"),
        )

        override suspend fun getPhotos() = photos
        override suspend fun removePhoto(photoId: String) {
            photos = photos.filterNot { it.id == photoId }
        }
    }

    /** Browse every image, cancel a removal, then remove one (US 03.06.01, 03.03.01). */
    @Test
    fun browseAndRemoveImage() {
        open()
        compose.onNodeWithText("Images").performClick()
        waitFor("Uploaded images")
        compose.onNodeWithText("Two Jack Lakeside").assertIsDisplayed()
        compose.onNodeWithText("By Jane Doe").assertIsDisplayed()
        compose.onNodeWithText("Cover photo").assertIsDisplayed()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
        compose.onNodeWithText("By Jill Poe").assertIsDisplayed()

        // The first card is the Two Jack Lakeside cover.
        compose.onAllNodesWithText("Remove image")[0].performClick()
        waitFor("Remove this image?")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, images.photos.size) }

        compose.onAllNodesWithText("Remove image")[1].performClick()
        waitFor("Remove this image?")
        compose.onNodeWithText("Remove").performClick()
        waitFor("Removed an image from Pine Ridge.")
        compose.runOnIdle { assertEquals(listOf("p1"), images.photos.map { it.id }) }
        compose.onNodeWithText("By Jill Poe").assertDoesNotExist()
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
                AppNavigation(controller, navigation, campsites, details = details, admin = admin, images = images)
            }
        }
        waitFor("Explore campsites")
        compose.runOnIdle { navigation.navigate("admin") }
        waitFor("Campsite listings")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text)[0].assertIsDisplayed()
    }
}
