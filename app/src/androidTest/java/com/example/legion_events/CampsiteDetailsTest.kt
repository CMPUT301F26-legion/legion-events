package com.example.legion_events

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.data.decodePhoto
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Exercises details with fake data; no production accounts, listings, or lottery entries are changed. */
class CampsiteDetailsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val site = Campsite("pine", "alice", "Pine Ridge", "Banff", 6, 6550,
        description = "A quiet riverside campsite.", amenities = listOf("Drinking water"),
        rules = listOf("Quiet after 10 pm."), cancellationPolicy = "Cancel seven days before arrival.",
        availableNights = listOf("2027-05-14", "2027-05-15"), photoId = "cover",
        ratingAverage = 4.7, ratingCount = 28)

    @Test fun listingOpensDetailsAndBackPreservesSearchAfterRestoration() {
        val repository = FakeDetailsRepository()
        openSearch(repository)
        compose.onNodeWithContentDescription("Scan campsite QR code (coming soon)").assertIsNotEnabled()
        compose.onNodeWithText("Search campsites").performTextInput("Banff")
        compose.onNodeWithText("Search campsites").performImeAction()
        compose.onNodeWithText("4+ guests").performClick()
        compose.onNodeWithText("Pine Ridge").performClick()
        waitForText("Back to search")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Pine Ridge photo 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Pine Ridge photo 1").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Quiet after 10 pm."))
        compose.onNodeWithText("Quiet after 10 pm.").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Enter lottery"))
        compose.onNodeWithText("2027-05-14\n2027-05-15").assertIsDisplayed()
        compose.onNodeWithText("Enter lottery").assertIsNotEnabled()

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals("campsite/{campsiteId}", navigation.currentDestination?.route)
            assertEquals("pine", navigation.currentBackStackEntry?.arguments?.getString("campsiteId"))
            assertEquals(1, repository.listingCalls)
            assertEquals(1, repository.photoCalls)
            navigation.popBackStack()
        }
        waitForText("Explore campsites")
        compose.onNodeWithText("Search campsites").assertTextContains("Banff")
        compose.onNodeWithText("4+ guests").assertIsSelected()
        compose.runOnIdle { assertNull(navigation.previousBackStackEntry) }
    }

    @Test fun listingAndPhotoFailuresOfferSeparateRetries() {
        val repository = FakeDetailsRepository().apply { failListing = true; failPhotos = true }
        openSearch(repository)
        compose.onNodeWithText("Pine Ridge").performClick()
        waitForText("Could not load this campsite. Check your connection and retry.")
        compose.onNodeWithText("Enter lottery").assertDoesNotExist()
        compose.runOnIdle { repository.failListing = false }
        compose.onNodeWithText("Retry").performClick()
        waitForText("Retry photos")
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
        compose.runOnIdle { repository.failPhotos = false }
        compose.onNodeWithText("Retry photos").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Pine Ridge photo 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(2, repository.listingCalls)
            assertEquals(2, repository.photoCalls)
        }
    }

    @Test fun photosAreDownsampledAndInvalidDataHasAFallback() {
        val photo = testPhoto(width = 2401)
        val image = checkNotNull(decodePhoto(photo))
        assertTrue(image.width <= 1200)
        image.recycle()
        assertNull(decodePhoto(photo.copy(imageBase64 = "not an image")))
        assertNull(decodePhoto(photo.copy(mimeType = "text/plain")))
    }

    /** Starts the actual router with fake account, list, and details repositories. */
    private fun openSearch(details: CampsiteDetailsRepository) {
        lateinit var onboarding: OnboardingController
        compose.runOnUiThread {
            onboarding = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("alice")
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(listOf(site))
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme { AppNavigation(onboarding, navigation, campsites, details) }
        }
        waitForText("Pine Ridge")
    }

    /** Waits for asynchronous controller state to reach the UI. */
    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Creates real compressed image data so the gallery test exercises native decoding. */
    private fun testPhoto(width: Int = 4): Photo {
        val bitmap = Bitmap.createBitmap(width, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return Photo("cover", "alice", "campsite", "pine", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), "image/png")
    }

    /** Simulates listing and photo failures independently without contacting Firestore. */
    private inner class FakeDetailsRepository : CampsiteDetailsRepository {
        var failListing = false
        var failPhotos = false
        var listingCalls = 0
        var photoCalls = 0

        /** Returns the selected listing or the test's requested failure. */
        override suspend fun getCampsite(campsiteId: String): Campsite {
            assertEquals(site.id, campsiteId)
            listingCalls++
            if (failListing) error("Unavailable")
            return site
        }

        /** Returns a real image only for this listing and cover ID. */
        override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?): List<Photo> {
            assertEquals(site.id, campsiteId)
            assertEquals(site.photoId, coverPhotoId)
            photoCalls++
            if (failPhotos) error("Unavailable")
            return listOf(testPhoto())
        }
    }
}
