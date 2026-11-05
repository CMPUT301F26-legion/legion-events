package com.example.legion_events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.WaitlistEntrant
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.Calendar

/**
 * Walks the owner lottery screens (Lotteries tab, S15, S16) with fake repositories,
 * so no real lotteries change (US 02.04.01, 02.04.05, 02.04.06, 02.09.01).
 */
class LotteryNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val campsite = Campsite(
        id = "lake", ownerId = "owner", name = "Two Jack Lakeside", locationName = "Banff", maxGuests = 6, pricePerNight = 3800,
    )
    /** A lottery whose registration opens in 10 days, so it can still be edited. */
    private var stored = Lottery(
        id = "L1", ownerId = "owner", campsiteIds = listOf("lake"),
        registrationStartAt = daysFromToday(10), registrationEndAt = daysFromToday(15),
        bookingStartAt = daysFromToday(20), bookingEndAt = daysFromToday(30),
        winnerCount = 3, waitlistLimit = 50,
    )
    private val waitlist = listOf(
        WaitlistEntrant("a", "joined", daysFromToday(-2)),
        WaitlistEntrant("b", "withdrawn", daysFromToday(-3)),
        WaitlistEntrant("c", "joined", daysFromToday(-1)),
    )
    private var created: Lottery? = null
    private val updates = mutableListOf<Lottery>()
    private lateinit var navigation: NavHostController

    private val owners = object : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = listOf(campsite)
        override suspend fun getMyCampsite(campsiteId: String) = campsite.takeIf { it.id == campsiteId }
        override suspend fun getMyLotteries() = listOf(stored)
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?) = campsite.id
    }

    private val lotteries = object : LotteryRepository {
        override suspend fun createLottery(lottery: Lottery): String {
            created = lottery
            return "new"
        }

        override suspend fun updateLottery(lottery: Lottery) {
            updates += lottery
            stored = lottery.copy(ownerId = stored.ownerId)
        }

        override suspend fun getLottery(id: String) = stored.takeIf { it.id == id } ?: error("missing")
        override suspend fun getMyLotteries() = listOf(stored)
        override suspend fun getWaitlist(lotteryId: String) = if (lotteryId == stored.id) waitlist else emptyList()
    }

    @Test
    fun lotteriesTabOpensManagementAndEditSavesBackToIt() {
        open()
        compose.runOnIdle { navigation.navigate("owner/lottery") }
        waitFor("My Lotteries")
        compose.onNodeWithText("Two Jack Lakeside").performClick()

        // S16 shows only entrants still waiting, earliest first (US 02.09.01).
        waitFor("Waiting list (2)")
        compose.onNodeWithText("Entrant 1").assertIsDisplayed()
        compose.onNodeWithText("Entrant 2").assertIsDisplayed()
        compose.onNodeWithText("Winner spots").assertIsDisplayed()

        // Edit opens S15 filled with the saved values (US 02.04.06).
        compose.onNodeWithText("Edit").performClick()
        waitFor("Edit lottery")
        compose.onNode(hasText("Number of winners") and hasSetTextAction()).performTextReplacement("5")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        // Saving returns to S16, which reloads.
        waitFor("Waiting list (2)")
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(5, updates.single().winnerCount)
            assertEquals(listOf("lake"), updates.single().campsiteIds)
            assertEquals("owner/lottery/{lotteryId}", navigation.currentDestination?.route)
        }
    }

    @Test
    fun createLotteryShowsErrorsUntilFieldsAreFilled() {
        open()
        compose.runOnIdle { navigation.navigate("owner/lottery") }
        waitFor("My Lotteries")
        compose.onNodeWithText("Create Lottery", useUnmergedTree = true).performClick()
        waitFor("Registration period")

        // Header and button share the text "Create lottery"; the button is the second one.
        compose.onAllNodesWithText("Create lottery")[1].performScrollTo().performClick()
        waitFor("Choose at least one campsite.")
        compose.onNodeWithText("Enter at least 1 winner.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(created) }

        // Choosing a campsite clears only that error (US 02.04.01).
        compose.onNodeWithText("Two Jack Lakeside", substring = true).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose at least one campsite.").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Enter at least 1 winner.").performScrollTo().assertIsDisplayed()
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
            LegioneventsTheme { AppNavigation(controller, navigation, campsites, owners = owners, lotteries = lotteries) }
        }
        waitFor("Explore campsites")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private companion object {
        /** Midnight in the device's time zone, [days] from today; matches how S15 stores days. */
        fun daysFromToday(days: Int): Timestamp {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.DAY_OF_MONTH, days)
            return Timestamp(calendar.time)
        }
    }
}
