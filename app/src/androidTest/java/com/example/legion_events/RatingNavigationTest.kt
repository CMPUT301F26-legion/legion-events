/*
 * RatingNavigationTest.kt
 *
 * Purpose: Verify completed-stay rating navigation, draft restoration, updates, and errors on S10c.
 * Role: Android UI tests through AppNavigation using synthetic accounts, bookings, and ratings.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.rateStayRoute
import com.example.legion_events.controller.reservationDetailsRoute
import com.example.legion_events.data.*
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Rating
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises S10 → S10b → S10c without authentication or Firestore network calls. */
class RatingNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val time = Timestamp.now().seconds
    private val completed = ReservationEntry(Reservation("booking #1", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(time - 7200, 0), Timestamp(time - 3600, 0), 2, 6550, true, "confirmed"), "Pine Ridge", "Banff")
    private val ratings = FakeRatings()
    private val entries = listOf(completed,
        completed.copy(reservation = completed.reservation.copy(id = "future", endDate = Timestamp(time + 3600, 0))),
        completed.copy(reservation = completed.reservation.copy(id = "cancelled", status = "cancelled")),
        completed.copy(reservation = completed.reservation.copy(id = "pending", cancellationRequested = true)))

    @Test fun completedStayOpensS10cRestoresItsDraftAndReturnsToPastAfterSaving() {
        open()
        compose.onNodeWithText("Bookings").performClick()
        compose.onNodeWithText("Past").performClick()
        waitFor("View reservation")
        // Pending cancellation is also in Past; only the completed booking can be rated.
        compose.onAllNodesWithText("View reservation")[0].performClick()
        waitFor("Total: $65.50 CAD")
        compose.onNodeWithText("Rate your stay").performScrollTo().performClick()
        waitFor("Choose a rating")
        compose.onNodeWithText("Bookings").assertIsSelected()
        compose.onNodeWithText("Save rating").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("booking #1", navigation.currentBackStackEntry?.arguments?.getString("reservationId")) }
        compose.onNodeWithContentDescription("4 stars").performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        waitFor("4 out of 5 stars")
        compose.runOnIdle { assertEquals(0, ratings.saves); assertEquals(1, ratings.reads) }
        compose.onNodeWithText("Save rating").performClick()
        waitFor("My reservations")
        compose.onNodeWithText("Past").assertIsSelected()
        compose.runOnIdle { assertEquals(1, ratings.saves); assertEquals(4, ratings.existing?.score) }
        waitFor("View reservation")
        compose.onAllNodesWithText("View reservation")[0].performClick()
        waitFor("Rate your stay")
        compose.onNodeWithText("Rate your stay").performScrollTo().performClick()
        waitFor("4 out of 5 stars")
        compose.onNodeWithContentDescription("2 stars").performClick()
        compose.onNodeWithText("Save rating").performClick()
        waitFor("My reservations")
        compose.runOnIdle { assertEquals(2, ratings.saves); assertEquals(2, ratings.existing?.score) }
    }

    @Test fun maybeLaterDiscardsTheVisitWithoutSaving() {
        open()
        compose.runOnIdle { navigation.navigate(rateStayRoute(completed.reservation.id)) }
        waitFor("Choose a rating")
        compose.onNodeWithContentDescription("5 stars").performClick()
        compose.onNodeWithText("Maybe later").performScrollTo().performClick()
        waitFor("Explore campsites")
        compose.runOnIdle { assertEquals(0, ratings.saves) }
    }

    @Test fun readAndSaveFailuresOfferRetryWithoutLosingTheSelectionInDarkMode() {
        val pending = CompletableDeferred<RatingDetails?>()
        ratings.load = { pending.await() }
        open(dark = true)
        compose.runOnIdle { navigation.navigate(rateStayRoute(completed.reservation.id)) }
        waitFor("Loading your stay…")
        compose.onNodeWithText("Save rating").assertDoesNotExist()
        pending.completeExceptionally(IllegalStateException("Unavailable"))
        waitFor("Could not load this stay's rating. Check your connection and retry.")
        compose.runOnIdle { ratings.load = { RatingDetails(completed, null) }; ratings.failSave = true }
        compose.onNodeWithText("Retry").performClick()
        waitFor("Choose a rating")
        compose.onNodeWithContentDescription("3 stars").performClick()
        compose.onNodeWithText("Save rating").performClick()
        waitFor("Could not save your rating. Check your connection or access, then retry.")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("3 out of 5 stars")
        compose.runOnIdle { assertEquals(1, ratings.saves); ratings.failSave = false }
        compose.onNodeWithText("Retry save").performClick()
        waitFor("My reservations")
        compose.runOnIdle { assertEquals(2, ratings.saves); assertEquals(3, ratings.existing?.score) }
    }

    @Test fun unfinishedCancelledAndPendingCancellationStaysHaveNoRateActionOrDirectAccess() {
        open()
        for (id in listOf("future", "cancelled", "pending")) {
            compose.runOnIdle { navigation.navigate(reservationDetailsRoute(id)) }
            waitFor("Total: $65.50 CAD")
            compose.onNodeWithText("Rate your stay").assertDoesNotExist()
        }
        compose.runOnIdle { navigation.navigate(rateStayRoute("future")) }
        waitFor("Only your completed stays without a pending cancellation at an available campsite can be rated.")
        compose.onNodeWithText("Save rating").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, ratings.saves) }
    }

    /** Starts the real shared router with fake account, booking, and rating services. */
    private fun open(dark: Boolean = false) {
        lateinit var account: OnboardingController
        compose.runOnUiThread {
            account = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("alice")
            })
        }
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(emptyList<Campsite>()) }
        val reservations = object : ReservationRepository {
            override suspend fun getMyReservations() = entries
            override suspend fun getReservation(reservationId: String) = entries.firstOrNull { it.reservation.id == reservationId }
            override suspend fun requestCancellation(reservationId: String): ReservationEntry = error("Unexpected cancellation")
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme(darkTheme = dark) {
                AppNavigation(account, navigation, campsites = campsites, reservations = reservations, ratings = ratings)
            }
        }
        waitFor("Explore campsites")
    }

    /** Waits until controller state has been rendered by the Compose screen. */
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Shares one in-memory score across visits to the same campsite, like the Firestore document key. */
    private inner class FakeRatings : RatingRepository {
        var existing: Rating? = null
        var reads = 0
        var saves = 0
        var failSave = false
        var load: suspend () -> RatingDetails? = { RatingDetails(completed, existing) }
        override suspend fun getForStay(reservationId: String): RatingDetails? {
            reads++
            return if (reservationId == completed.reservation.id) load() else null
        }
        override suspend fun saveForStay(reservationId: String, score: Int): Rating {
            assertEquals(completed.reservation.id, reservationId)
            saves++
            if (failSave) error("Unavailable")
            return Rating("pine", "alice", score, existing?.createdAt ?: Timestamp.now(), Timestamp.now()).also { existing = it }
        }
    }
}
