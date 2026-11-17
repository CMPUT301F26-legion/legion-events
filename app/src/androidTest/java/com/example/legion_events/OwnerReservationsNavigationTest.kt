/*
 * OwnerReservationsNavigationTest.kt
 *
 * Purpose: Intent tests for S17 owner reservations: campsite dropdown, booked-night calendar,
 *   Active/Past/Cancelled tabs, booking details, and rejection
 *   (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: Drives the real router with fake owner repositories so no real bookings change.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.OwnerReservationRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.monthOf
import com.example.legion_events.model.monthTitle
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Walks S17 with synthetic campsites and bookings relative to the current time. */
class OwnerReservationsNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var navigation: NavHostController
    private val day = 24 * 60 * 60L
    /** Start of today in UTC, in seconds, so stays land on whole nights. */
    private val today = Timestamp.now().seconds / day * day
    private val lake = Campsite("lake", "owner", "Two Jack Lakeside", "Banff", 6, 3800)
    private val creek = Campsite("creek", "owner", "Whistlers Campground", "Jasper", 4, 2500)
    private val bookings = FakeBookings()

    private val owners = object : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = listOf(lake, creek)
        override suspend fun getMyCampsite(campsiteId: String) = listOf(lake, creek).find { it.id == campsiteId }
        override suspend fun getMyLotteries() = emptyList<Lottery>()
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?) = campsite.id
    }

    @Test
    fun ownerSeesActivePastAndCancelledBookingsAndTheCalendar() {
        bookings.stored = mutableListOf(
            entry("alex", today + day, today + 3 * day),
            entry("noah", today - 6 * day, today - 4 * day),
            entry("mia", today + 5 * day, today + 6 * day, Reservation.STATUS_CANCELLED),
        )
        openReservations()
        compose.onNodeWithText("Two Jack Lakeside").assertIsDisplayed()
        compose.onNodeWithText(monthTitle(monthOf(Timestamp(today + day, 0)))).assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsSelected()
        card("alex").performScrollTo().assertIsDisplayed()
        card("noah").assertDoesNotExist()

        compose.onNodeWithText("Past").performScrollTo().performClick()
        waitFor("Reservation noah", substring = true)
        card("alex").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertDoesNotExist()

        compose.onNodeWithText("Cancelled").performScrollTo().performClick()
        waitFor("Reservation mia", substring = true)
        compose.runOnIdle { assertEquals(listOf("lake"), bookings.reads) }
    }

    @Test
    fun calendarMarksBookedNightsAndMovesBetweenMonths() {
        bookings.stored = mutableListOf(entry("alex", today + day, today + 2 * day))
        openReservations()
        val month = monthOf(Timestamp(today + day, 0))
        compose.onNodeWithText("1 night booked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next month").performClick()
        waitFor(monthTitle(com.example.legion_events.model.shiftMonth(month, 1)))
        compose.onNodeWithText("0 nights booked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous month").performClick()
        waitFor(monthTitle(month))
    }

    @Test
    fun viewBookingShowsEntrantDatesGuestsAndRules() {
        bookings.stored = mutableListOf(entry("alex", today + day, today + 3 * day))
        openReservations()
        compose.onNodeWithText("View booking").performScrollTo().performClick()
        waitFor("Booking details")
        compose.onNodeWithText("Reservation number: alex").assertIsDisplayed()
        compose.onNodeWithText("Guests: 2").assertIsDisplayed()
        compose.onNodeWithText("Rules accepted: Yes").assertIsDisplayed()
        compose.onNodeWithText("Total: $76.00 CAD").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Booking details").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun rejectingWithAReasonMovesTheBookingToCancelled() {
        bookings.stored = mutableListOf(entry("alex", today + day, today + 3 * day))
        openReservations()
        compose.onNodeWithText("Reject").performScrollTo().performClick()
        waitFor("Reject this reservation?")
        compose.onNodeWithText("Keep booking").performClick()
        compose.runOnIdle { assertEquals(emptyList<Pair<String, String?>>(), bookings.rejected) }

        compose.onNodeWithText("Reject").performScrollTo().performClick()
        waitFor("Reject this reservation?")
        compose.onNodeWithText("Reason (optional)").performTextInput("Fire ban")
        compose.onNodeWithText("Reject booking").performClick()
        waitFor("Reservation rejected. The entrant was notified.")
        compose.runOnIdle { assertEquals(listOf("alex" to "Fire ban"), bookings.rejected) }
        compose.onNodeWithText("No active reservations").assertIsDisplayed()
        compose.onNodeWithText("Cancelled").performScrollTo().performClick()
        waitFor("Reservation alex", substring = true)
        compose.onNodeWithText("Rejected").assertIsDisplayed()
    }

    @Test
    fun choosingAnotherCampsiteShowsItsBookings() {
        bookings.stored = mutableListOf(
            entry("alex", today + day, today + 3 * day),
            entry("sam", today + day, today + 3 * day, campsite = "creek"),
        )
        openReservations()
        card("alex").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Two Jack Lakeside").performClick()
        waitFor("Whistlers Campground")
        compose.onAllNodesWithText("Whistlers Campground")[0].performClick()
        waitFor("Reservation sam", substring = true)
        card("alex").assertDoesNotExist()
    }

    /** Starts the router, opens the owner dashboard, and taps the Reservations tab. */
    private fun openReservations() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("owner")
            })
        }
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(emptyList<Campsite>()) }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme {
                AppNavigation(controller, navigation, campsites = campsites, owners = owners,
                    ownerReservations = bookings)
            }
        }
        waitFor("Explore campsites")
        compose.runOnIdle { navigation.navigate("owner") }
        waitFor("My Campsites")
        compose.onNodeWithText("Reservations").performClick()
        waitFor("Booked")
        compose.runOnIdle { assertEquals("owner/reservations", navigation.currentDestination?.route) }
    }

    /**
     * Waits for asynchronous controller state to be rendered.
     * @param text Text that should appear.
     * @param substring Whether [text] may be part of a longer label.
     */
    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Finds a booking card by its reservation number.
     * @param id The reservation ID.
     * @return The card's guests-and-number label.
     */
    private fun card(id: String) = compose.onNodeWithText("Reservation $id", substring = true)

    /**
     * Builds a booking at a campsite.
     * @param id Reservation ID.
     * @param start Check-in in epoch seconds.
     * @param end Checkout in epoch seconds.
     * @param status Stored status.
     * @param campsite Campsite ID.
     * @return The booking.
     */
    private fun entry(id: String, start: Long, end: Long, status: String = Reservation.STATUS_CONFIRMED,
                      campsite: String = "lake") =
        Reservation(id, "lottery", "offer", campsite, "user-$id", "owner", Timestamp(start, 0), Timestamp(end, 0),
            2, 7600, true, status)

    /** In-memory bookings; a rejection changes the stored status like the real transaction. */
    private class FakeBookings : OwnerReservationRepository {
        /** Every stored booking. */
        var stored = mutableListOf<Reservation>()
        /** Campsite IDs read, in order. */
        val reads = mutableListOf<String>()
        /** Rejected reservation IDs with their reasons. */
        val rejected = mutableListOf<Pair<String, String?>>()

        override suspend fun getCampsiteReservations(campsiteId: String): List<Reservation> {
            reads += campsiteId
            return stored.filter { it.campsiteId == campsiteId }
        }

        override suspend fun rejectReservation(reservationId: String, reason: String?) {
            rejected += reservationId to reason
            stored = stored.map {
                if (it.id == reservationId) it.copy(status = Reservation.STATUS_REJECTED) else it
            }.toMutableList()
        }
    }
}
