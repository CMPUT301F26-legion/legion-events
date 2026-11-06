/*
 * WaitlistNavigationTest.kt
 *
 * Purpose: Intent tests for joining and leaving a lottery waitlist and for owner notices
 *   (US 01.01.06, 01.02.01, 01.02.02, 02.10.01, 02.10.02).
 * Role: Drives the real router and screens with in-memory repositories; no Firebase data changes.
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
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.NotificationRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.OwnerNoticeRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.data.WaitlistRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.OwnerNotice
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.WaitlistEntrant
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Walks S04 → S06 for an entrant and S18 → S07 for an owner's notice, with fake data. */
class WaitlistNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigation: NavHostController

    private val campsite = Campsite(
        id = "lake", ownerId = "owner", name = "Two Jack Lakeside", locationName = "Banff", maxGuests = 6, pricePerNight = 3800,
    )
    /** Registration opened yesterday and closes in 10 days. */
    private var lottery = Lottery(
        id = "L1", ownerId = "owner", campsiteIds = listOf("lake"),
        registrationStartAt = daysFromNow(-1), registrationEndAt = daysFromNow(10),
        bookingStartAt = daysFromNow(11), bookingEndAt = daysFromNow(20), winnerCount = 2, waitlistLimit = 5,
    )
    /** The test user's waitlist status, or null before joining. */
    private var status: String? = null
    /** Notices sent through S18, also delivered to the test user's inbox. */
    private val inbox = MutableStateFlow<List<OwnerNotice>>(emptyList())

    private val waitlists = object : WaitlistRepository {
        override suspend fun getLotteriesForCampsite(campsiteId: String) = listOf(LotteryEntry(lottery, status))
        override suspend fun getMyEntries() =
            status?.let { listOf(LotteryEntry(lottery, it, listOf(campsite.name))) }.orEmpty()

        override suspend fun join(lotteryId: String) {
            status = WaitlistEntrant.STATUS_JOINED
            lottery = lottery.copy(entrantCount = lottery.entrantCount + 1)
        }

        override suspend fun leave(lotteryId: String) {
            status = WaitlistEntrant.STATUS_WITHDRAWN
            lottery = lottery.copy(entrantCount = lottery.entrantCount - 1)
        }
    }

    private val notices = object : OwnerNoticeRepository {
        override suspend fun sendNotification(lotteryId: String, message: String): Int {
            val recipients = if (status == WaitlistEntrant.STATUS_JOINED) listOf("user") else emptyList()
            check(recipients.isNotEmpty()) { "Nobody is on this waitlist yet." }
            inbox.value = listOf(OwnerNotice("n${inbox.value.size}", "owner", lotteryId, message, recipients,
                Timestamp.now())) + inbox.value
            return recipients.size
        }

        override suspend fun getSentNotices() = inbox.value
        override fun observeReceived(userId: String) = inbox
    }

    private val details = object : CampsiteDetailsRepository {
        override suspend fun getCampsite(campsiteId: String) = campsite.takeIf { it.id == campsiteId }
        override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?) = emptyList<Photo>()
    }

    private val owners = object : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = listOf(campsite)
        override suspend fun getMyCampsite(campsiteId: String) = campsite.takeIf { it.id == campsiteId }
        override suspend fun getMyLotteries() = listOf(lottery)
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?) = campsite.id
    }

    private val lotteries = object : LotteryRepository {
        override suspend fun createLottery(lottery: Lottery): String = error("Unexpected lottery creation")
        override suspend fun updateLottery(lottery: Lottery): Unit = error("Unexpected lottery edit")
        override suspend fun getLottery(id: String) = lottery
        override suspend fun getMyLotteries() = listOf(lottery)
        override suspend fun getWaitlist(lotteryId: String) =
            status?.let { listOf(WaitlistEntrant("user", it, null, lotteryId)) }.orEmpty()
    }

    /** Join from S04 shows S06b's confirmation, S06 lists the entry, and Leave asks first (US 01.01.06, 01.02.01, 01.02.02). */
    @Test
    fun joinFromDetailsThenLeaveFromMyLotteries() {
        open()
        compose.runOnIdle { navigation.navigate("campsite/lake") }
        waitFor("Join lottery")
        compose.onNodeWithText("Join lottery").performScrollTo().performClick()
        waitFor("You joined the waiting list. Good luck in the draw!")
        compose.runOnIdle { assertEquals(WaitlistEntrant.STATUS_JOINED, status) }

        compose.onNodeWithText("View my lotteries").performScrollTo().performClick()
        waitFor("On the waiting list")
        compose.onNodeWithText("Two Jack Lakeside").assertIsDisplayed()

        compose.onNodeWithText("Leave waiting list").performScrollTo().performClick()
        waitFor("Leave waiting list?")
        compose.onNodeWithText("Stay").performClick()
        compose.runOnIdle { assertEquals(WaitlistEntrant.STATUS_JOINED, status) }

        compose.onNodeWithText("Leave waiting list").performScrollTo().performClick()
        waitFor("Leave waiting list?")
        compose.onNodeWithText("Leave").performClick()
        waitFor("Left the waiting list")
        compose.runOnIdle { assertEquals(WaitlistEntrant.STATUS_WITHDRAWN, status) }
    }

    /** An owner sends a notice from S18, sees it in the sent list, and the entrant sees it in S07 (US 02.10.01, 02.10.02). */
    @Test
    fun ownerSendsNoticeThatReachesTheInbox() {
        status = WaitlistEntrant.STATUS_JOINED
        open()
        compose.runOnIdle { navigation.navigate("owner/notifications") }
        waitFor("Send notice")
        compose.onNode(hasSetTextAction()).performTextInput("The road is closed until Friday.")
        compose.onNodeWithText("Send notice").performScrollTo().performClick()
        waitFor("Sent to 1 entrant.")
        compose.onNodeWithText("The road is closed until Friday.").assertIsDisplayed()

        compose.runOnIdle { navigation.navigate("inbox") }
        waitFor("Notice from a campsite owner")
        compose.onNodeWithText("The road is closed until Friday.").assertIsDisplayed()
    }

    private fun open() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("user")
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(emptyList<Campsite>())
        }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme {
                AppNavigation(controller, navigation, campsites, details = details, owners = owners,
                    lotteries = lotteries, notifications = NotificationRepository { flowOf(emptyList()) },
                    waitlists = waitlists, notices = notices)
            }
        }
        waitFor("Explore campsites")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text)[0].assertIsDisplayed()
    }

    private companion object {
        /** A time [days] from now. */
        fun daysFromNow(days: Int) = Timestamp(java.util.Date(System.currentTimeMillis() + days * 86_400_000L))
    }
}
