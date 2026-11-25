/*
 * AdminLogsNavigationTest.kt
 *
 * Purpose: Intent tests for S20 Admin logs: review every notification (US 03.08.01) and every
 *   owner-to-entrant message (US 03.12.01).
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
import com.example.legion_events.data.AdminLogRepository
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ModeratedCampsite
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.AdminLogCategory
import com.example.legion_events.model.AdminLogEntry
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/** Walks S20 Admin logs as an admin, with fake data. */
class AdminLogsNavigationTest {
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

    private val logs = object : AdminLogRepository {
        override suspend fun getNotifications() = listOf(
            AdminLogEntry("n1", AdminLogCategory.NOTIFICATION, "lottery_won", null, listOf("jane"), "", "L1",
                Timestamp.now()),
        )
        override suspend fun getOwnerMessages() = listOf(
            AdminLogEntry("m1", AdminLogCategory.MESSAGE, AdminLogEntry.TYPE_OWNER_NOTICE, "jill", listOf("jane", "jill"),
                "Bring bear spray.", "L1", Timestamp.now()),
        )
    }

    /** Review the notification log, then the owner-to-entrant message log (US 03.08.01, 03.12.01). */
    @Test
    fun reviewNotificationAndMessageLogs() {
        open()
        compose.onNodeWithText("Logs").performClick()
        waitFor("Notifications (1)")
        compose.onNodeWithText("Lottery won").assertIsDisplayed()
        compose.onNodeWithText("CampLot → Jane Doe").assertIsDisplayed()

        compose.onNodeWithText("Messages (1)").performClick()
        waitFor("Notice to entrants")
        compose.onNodeWithText("Jill Poe → 2 entrants").assertIsDisplayed()
        compose.onNodeWithText("Bring bear spray.").assertIsDisplayed()
        compose.onNodeWithText("Lottery won").assertDoesNotExist()
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
                AppNavigation(controller, navigation, campsites, details = details, admin = admin, logs = logs)
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
