package com.example.legion_events

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.reservationDetailsRoute
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises S10/S10b through the real router using only synthetic reservations and accounts. */
class ReservationNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val account = mutableStateOf<OnboardingController?>(null)
    private val time = Timestamp.now().seconds
    private val upcoming = entry("booking #1", time + 3600, time + 7200, name = "Pine Ridge")

    @Test fun bookingsShowActiveReservationsAndOwnedDetailsRestoreUnderTheBookingsTab() {
        val repository = FakeReservations().apply {
            entries = listOf(upcoming, entry("past", time - 7200, time - 3600, name = "Past camp"),
                entry("cancelled", time + 1, time + 1000, "cancelled", "Cancelled camp"))
        }
        open(repository)
        compose.onNodeWithText("Bookings").performClick()
        waitFor("View reservation")
        compose.onNodeWithText("Active").assertIsSelected()
        compose.onNodeWithText("Bookings").assertIsSelected()
        compose.onNodeWithText("Pine Ridge").assertIsDisplayed()
        compose.onNodeWithText("Past camp").assertDoesNotExist()
        compose.onNodeWithText("Cancelled camp").assertDoesNotExist()
        compose.onNodeWithText("View reservation").performClick()
        waitFor("Total: $65.50 CAD")
        compose.onNodeWithText("Bookings").assertIsSelected()
        compose.onNodeWithText("Guests: 4").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(upcoming.reservation.id, navigation.currentBackStackEntry?.arguments?.getString("reservationId"))
        }
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Total: $65.50 CAD")
        compose.runOnIdle { assertEquals(1, repository.detailReads) }
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("View reservation")
        compose.onNodeWithText("Active").assertIsSelected()
        compose.runOnIdle { assertEquals("bookings", navigation.currentDestination?.route) }
    }

    @Test fun loadingFailureEmptyAndRefreshStatesWorkInDarkMode() {
        val pending = CompletableDeferred<List<ReservationEntry>>()
        val repository = FakeReservations().apply { load = { pending.await() } }
        open(repository, dark = true)
        compose.onNodeWithText("Bookings").performClick()
        waitFor("Loading reservations…")
        compose.onNodeWithText("View reservation").assertDoesNotExist()
        pending.completeExceptionally(IllegalStateException("Unavailable"))
        waitFor("Could not load your reservations. Check your connection and retry.")
        compose.runOnIdle { repository.load = { emptyList() } }
        compose.onNodeWithText("Retry").performClick()
        waitFor("No active reservations")
        compose.runOnIdle { repository.load = { listOf(upcoming) } }
        compose.onNodeWithText("Refresh").performClick()
        waitFor("View reservation")
    }

    @Test fun failedOrMissingDirectLinksShowNoReservationData() {
        val repository = FakeReservations().apply { failDetail = true }
        open(repository)
        compose.runOnIdle { navigation.navigate(reservationDetailsRoute(upcoming.reservation.id)) }
        waitFor("Could not load this reservation. Check your connection and retry.")
        compose.onNodeWithText("Total: $65.50 CAD").assertDoesNotExist()
        compose.runOnIdle { repository.failDetail = false; repository.entries = emptyList() }
        compose.onNodeWithText("Retry").performClick()
        waitFor("This reservation is not available for this account.")
        compose.onNodeWithText("View campsite").assertDoesNotExist()
    }

    @Test fun restoredBookingsWaitForTheCurrentAccountCheck() {
        val repository = FakeReservations()
        open(repository)
        compose.onNodeWithText("Bookings").performClick()
        waitFor("View reservation")
        val pending = CompletableDeferred<UserProfile>()
        compose.runOnIdle {
            account.value = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = pending.await()
            })
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Opening your account…").assertIsDisplayed()
        compose.onNodeWithText("Pine Ridge").assertDoesNotExist()
        pending.complete(UserProfile("alice", disabled = true))
        waitFor("Account disabled")
        compose.onNodeWithText("View reservation").assertDoesNotExist()
    }

    /** Starts the shared router with a synthetic account and empty campsite search results. */
    private fun open(repository: ReservationRepository, dark: Boolean = false) {
        compose.runOnUiThread {
            account.value = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("alice")
            })
        }
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(emptyList<Campsite>()) }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme(darkTheme = dark) {
                AppNavigation(checkNotNull(account.value), navigation, campsites = campsites, reservations = repository)
            }
        }
        waitFor("Explore campsites")
    }

    /** Waits for asynchronous controller state to be rendered. */
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Builds valid saved booking data relative to this test's current time. */
    private fun entry(id: String, start: Long, end: Long, status: String = "confirmed", name: String = "Camp") =
        ReservationEntry(Reservation(id, "lottery", "offer", "pine", "alice", "owner",
            Timestamp(start, 0), Timestamp(end, 0), 4, 6550, true, status), name, "Banff")

    /** Controls list and detail outcomes without reading or writing Firebase. */
    private inner class FakeReservations : ReservationRepository {
        var entries = listOf(upcoming)
        var load: suspend () -> List<ReservationEntry> = { entries }
        var failDetail = false
        var detailReads = 0
        override suspend fun getMyReservations() = load()
        override suspend fun getReservation(reservationId: String): ReservationEntry? {
            detailReads++
            if (failDetail) throw SecurityException("Unavailable to this account")
            return entries.firstOrNull { it.reservation.id == reservationId }
        }
    }
}
