/*
 * OwnerReservationsControllerTest.kt
 *
 * Purpose: Verify S17's campsite choice, booked nights, Active/Past/Cancelled lists, restoration,
 *   and rejection (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: Local JVM tests for OwnerReservationsController with an in-memory repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.OwnerReservationRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drives the controller with synthetic campsites and bookings around a fixed clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerReservationsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    /** 2027-07-04T00:00:00Z in seconds; the controller's "now". */
    private val at = 1_814_659_200L
    private val day = 24 * 60 * 60L
    private val lake = Campsite("lake", "owner", "Two Jack Lakeside", "Banff", 6, 3800)
    private val creek = Campsite("creek", "owner", "Aspen Creek", "Jasper", 4, 2500)
    private val repository = FakeOwnerReservations()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun splitsBookingsIntoActivePastAndCancelledInTheirOrders() = runTest(dispatcher) {
        repository.bookings["creek"] = listOf(
            entry("later", at + 5 * day, at + 7 * day), entry("ongoing", at - day, at + day),
            entry("old", at - 20 * day, at - 18 * day), entry("recent", at - 5 * day, at - 3 * day),
            entry("cancelled", at + day, at + 2 * day, Reservation.STATUS_CANCELLED, updated = at - 100),
            entry("rejected", at + day, at + 2 * day, Reservation.STATUS_REJECTED, updated = at - 10),
            entry("pending", at + day, at + 2 * day, "pending"),
            entry("requested", at + 3 * day, at + 4 * day, Reservation.STATUS_REQUESTED),
            entry("stale-request", at - 5 * day, at - 4 * day, Reservation.STATUS_REQUESTED),
        )
        val controller = loaded()
        val state = controller.state.value
        // New entrant bookings start as requested and must show before anything confirms them.
        assertEquals(listOf("ongoing", "requested", "later"), state.active.ids())
        assertEquals(listOf("recent", "old"), state.past.ids())
        assertEquals(listOf("rejected", "cancelled"), state.cancelled.ids())
        assertEquals(state.active, state.visibleEntries())
        controller.selectTab(OwnerReservationTab.CANCELLED)
        assertEquals(state.cancelled, controller.state.value.visibleEntries())
        assertEquals(1, repository.reads)
    }

    @Test fun calendarMarksConfirmedNightsOutlinesRequestedOnesAndOpensOnTheNextStay() = runTest(dispatcher) {
        repository.bookings["creek"] = listOf(
            entry("next", at + 30 * day, at + 32 * day), entry("past", at - 3 * day, at - 2 * day),
            entry("rejected", at + 1 * day, at + 2 * day, Reservation.STATUS_REJECTED),
            entry("asked", at + 40 * day, at + 42 * day, Reservation.STATUS_REQUESTED),
            entry("overlap", at + 31 * day, at + 33 * day, Reservation.STATUS_REQUESTED),
            entry("stale-request", at - 3 * day, at - 2 * day, Reservation.STATUS_REQUESTED),
        )
        val controller = loaded()
        val state = controller.state.value
        assertEquals(setOf("2027-08-03", "2027-08-04", "2027-07-01"), state.bookedNights)
        // A confirmed night is only shown as booked; finished requests are not shown.
        assertEquals(setOf("2027-08-05", "2027-08-13", "2027-08-14"), state.requestedNights)
        assertEquals("2027-08", state.month)
        controller.showMonth(-1)
        assertEquals("2027-07", controller.state.value.month)
    }

    @Test fun calendarOpensOnTheCurrentMonthWithoutUpcomingStays() = runTest(dispatcher) {
        val controller = loaded()
        assertEquals("2027-07", controller.state.value.month)
        assertTrue(controller.state.value.active.isEmpty())
    }

    @Test fun firstCampsiteByNameIsSelectedAndSwitchingReadsTheOther() = runTest(dispatcher) {
        repository.bookings["lake"] = listOf(entry("lake-stay", at + day, at + 2 * day))
        repository.bookings["creek"] = listOf(entry("creek-stay", at + day, at + 2 * day, campsite = "creek"))
        val controller = loaded()
        assertEquals(listOf("Aspen Creek", "Two Jack Lakeside"), controller.state.value.campsites.map { it.name })
        assertEquals("creek", controller.state.value.selectedCampsiteId)
        assertEquals(listOf("creek-stay"), controller.state.value.active.ids())
        controller.selectCampsite("lake")
        assertTrue(controller.state.value.loading)
        runCurrent()
        assertEquals(listOf("lake-stay"), controller.state.value.active.ids())
        assertEquals(listOf("creek", "lake"), repository.readCampsites)
        controller.selectCampsite("lake")
        controller.selectCampsite("someone-elses")
        runCurrent()
        assertEquals(2, repository.reads)
    }

    @Test fun aSlowCampsiteReadDoesNotOverwriteANewerSelection() = runTest(dispatcher) {
        val controller = loaded()
        val slow = CompletableDeferred<List<Reservation>>()
        repository.load = { id -> if (id == "lake") slow.await() else repository.bookings[id].orEmpty() }
        controller.selectCampsite("lake")
        runCurrent()
        controller.selectCampsite("creek")
        runCurrent()
        slow.complete(listOf(entry("stale", at + day, at + 2 * day)))
        runCurrent()
        assertEquals("creek", controller.state.value.selectedCampsiteId)
        assertTrue(controller.state.value.active.isEmpty())
        assertFalse(controller.state.value.loading)
    }

    @Test fun ownersWithoutCampsitesSeeNoBookings() = runTest(dispatcher) {
        val controller = controller(campsites = emptyList())
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.selectedCampsiteId)
        assertFalse(controller.state.value.loading)
        assertEquals(0, repository.reads)
    }

    @Test fun failuresShowARetryableErrorAndRetryRecovers() = runTest(dispatcher) {
        repository.load = { throw IllegalStateException("offline") }
        val controller = loaded()
        assertEquals("Could not load your reservations. Check your connection and retry.", controller.state.value.error)
        repository.load = { listOf(entry("stay", at + day, at + 2 * day)) }
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals(listOf("stay"), controller.state.value.active.ids())
    }

    @Test fun campsiteTabAndMonthSurviveRecreation() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = controller(saved = saved)
        first.refresh()
        runCurrent()
        first.selectCampsite("lake")
        first.selectTab(OwnerReservationTab.PAST)
        first.showMonth(2)
        runCurrent()
        val restored = OwnerReservationsController({ listOf(lake, creek) }, repository, saved) { Timestamp(at, 0) }
        assertEquals(OwnerReservationTab.PAST, restored.state.value.selectedTab)
        restored.refresh()
        runCurrent()
        assertEquals("lake", restored.state.value.selectedCampsiteId)
        assertEquals("2027-09", restored.state.value.month)
    }

    @Test fun viewingABookingOpensAndClosesItsDetails() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day)
        repository.bookings["creek"] = listOf(stay)
        val controller = loaded()
        controller.viewBooking(stay)
        assertEquals(stay, controller.state.value.viewing)
        controller.closeBooking()
        assertNull(controller.state.value.viewing)
    }

    @Test fun cancellingConfirmedBookingSendsTheTrimmedReasonAndMovesItToCancelled() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day, campsite = "creek")
        repository.bookings["creek"] = listOf(stay)
        val controller = loaded()
        controller.viewBooking(stay)
        controller.requestReject(stay)
        assertNull(controller.state.value.viewing)
        controller.setRejectReason("  Fire ban  ")
        controller.confirmReject()
        assertTrue(checkNotNull(controller.state.value.rejecting).inProgress)
        controller.confirmReject()
        runCurrent()
        assertEquals(listOf("stay" to "Fire ban"), repository.rejected)
        assertNull(controller.state.value.rejecting)
        assertEquals("Reservation cancelled. The entrant was notified.", controller.state.value.result)
        assertTrue(controller.state.value.active.isEmpty())
        assertEquals(listOf("stay"), controller.state.value.cancelled.ids())
        assertTrue(controller.state.value.bookedNights.isEmpty())
    }

    @Test fun requestedBookingsCanBeRejected() = runTest(dispatcher) {
        val asked = entry("asked", at + day, at + 2 * day, Reservation.STATUS_REQUESTED, campsite = "creek")
        repository.bookings["creek"] = listOf(asked)
        val controller = loaded()
        controller.requestReject(asked)
        controller.confirmReject()
        runCurrent()
        assertEquals(listOf("asked" to null), repository.rejected)
        assertEquals("Booking request rejected. The entrant was notified.", controller.state.value.result)
        assertEquals(listOf("asked"), controller.state.value.cancelled.ids())
        assertTrue(controller.state.value.requestedNights.isEmpty())
    }

    @Test fun refreshClearsTheRejectionConfirmation() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day, campsite = "creek")
        repository.bookings["creek"] = listOf(stay)
        val controller = loaded()
        controller.requestReject(stay)
        controller.confirmReject()
        runCurrent()
        assertNotNull(controller.state.value.result)
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.result)
    }

    @Test fun aBlankReasonIsSentAsNoReasonAndLongReasonsAreCut() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day, campsite = "creek")
        repository.bookings["creek"] = listOf(stay)
        val controller = loaded()
        controller.requestReject(stay)
        controller.setRejectReason("x".repeat(Reservation.MAX_REJECTION_REASON_LENGTH + 50))
        assertEquals(Reservation.MAX_REJECTION_REASON_LENGTH, controller.state.value.rejecting?.reason?.length)
        controller.setRejectReason("   ")
        controller.confirmReject()
        runCurrent()
        assertEquals(listOf("stay" to null), repository.rejected)
    }

    @Test fun pastAndCancelledBookingsCannotBeRejected() = runTest(dispatcher) {
        val past = entry("past", at - 3 * day, at - day, campsite = "creek")
        val cancelled = entry("cancelled", at + day, at + 2 * day, Reservation.STATUS_CANCELLED, campsite = "creek")
        val controller = loaded()
        controller.requestReject(past)
        controller.requestReject(cancelled)
        assertNull(controller.state.value.rejecting)
    }

    @Test fun cancellingTheDialogKeepsTheBooking() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day, campsite = "creek")
        val controller = loaded()
        controller.requestReject(stay)
        controller.cancelReject()
        runCurrent()
        assertNull(controller.state.value.rejecting)
        assertTrue(repository.rejected.isEmpty())
    }

    @Test fun aFailedRejectionKeepsTheDialogOpenWithTheReason() = runTest(dispatcher) {
        val stay = entry("stay", at + day, at + 2 * day, campsite = "creek")
        repository.bookings["creek"] = listOf(stay)
        val controller = loaded()
        controller.requestReject(stay)
        controller.setRejectReason("Closed")
        repository.reject = { throw IllegalStateException("Only active reservations can be rejected.") }
        controller.confirmReject()
        runCurrent()
        val draft = checkNotNull(controller.state.value.rejecting)
        assertEquals("Only active reservations can be rejected.", draft.error)
        assertEquals("Closed", draft.reason)
        assertFalse(draft.inProgress)
        repository.reject = { throw SecurityException("This reservation is not at your campsite.") }
        controller.confirmReject()
        runCurrent()
        assertEquals("This reservation is not at your campsite.", controller.state.value.rejecting?.error)
        repository.reject = { throw java.io.IOException("offline") }
        controller.confirmReject()
        runCurrent()
        assertEquals("Could not update the reservation. Check your connection and retry.",
            controller.state.value.rejecting?.error)
        controller.setRejectReason("Closed for repairs")
        assertNull(controller.state.value.rejecting?.error)
        assertNotNull(controller.state.value.rejecting)
        assertEquals(listOf("stay"), controller.state.value.active.ids())
    }

    /**
     * Creates a controller and runs its first load.
     * @return The loaded controller.
     */
    private fun kotlinx.coroutines.test.TestScope.loaded(): OwnerReservationsController =
        controller().also { it.refresh(); runCurrent() }

    /**
     * Creates a controller owned by a ViewModelStore so its scope is cleared after each test.
     * @param campsites The owner's campsites.
     * @param saved Saved state shared with a recreated controller.
     * @return The controller.
     */
    private fun controller(campsites: List<Campsite> = listOf(lake, creek), saved: SavedStateHandle = SavedStateHandle()) =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { OwnerReservationsController({ campsites }, repository, saved) { Timestamp(at, 0) } }
        })["owner-reservations-${System.nanoTime()}", OwnerReservationsController::class]

    /** The booking IDs in list order. */
    private fun List<Reservation>.ids() = map { it.id }

    /**
     * Builds a booking at a campsite.
     * @param id Reservation ID.
     * @param start Check-in in epoch seconds.
     * @param end Checkout in epoch seconds.
     * @param status Stored status.
     * @param campsite Campsite ID.
     * @param updated Last change in epoch seconds, or null.
     * @return The booking.
     */
    private fun entry(id: String, start: Long, end: Long, status: String = Reservation.STATUS_CONFIRMED,
                      campsite: String = "lake", updated: Long? = null) =
        Reservation(id, "lottery", "offer", campsite, "entrant-$id", "owner", Timestamp(start, 0), Timestamp(end, 0),
            2, 7600, true, status, updatedAt = updated?.let { Timestamp(it, 0) })

    /** In-memory bookings; rejecting changes the stored status like the real transaction. */
    private class FakeOwnerReservations : OwnerReservationRepository {
        /** Bookings by campsite ID. */
        val bookings = mutableMapOf<String, List<Reservation>>()
        /** Campsite IDs read, in order. */
        val readCampsites = mutableListOf<String>()
        /** Rejected reservation IDs with their reasons. */
        val rejected = mutableListOf<Pair<String, String?>>()
        /** How many campsite reads happened. */
        val reads get() = readCampsites.size
        /** Replaces the read behaviour. */
        var load: suspend (String) -> List<Reservation> = { bookings[it].orEmpty() }
        /** Replaces the rejection behaviour. */
        var reject: suspend (String) -> Unit = { id ->
            bookings.replaceAll { _, list ->
                list.map { if (it.id == id) it.copy(status = Reservation.STATUS_REJECTED) else it }
            }
        }

        override suspend fun getCampsiteReservations(campsiteId: String): List<Reservation> {
            readCampsites += campsiteId
            return load(campsiteId)
        }

        override suspend fun rejectReservation(reservationId: String, reason: String?) {
            reject(reservationId)
            rejected += reservationId to reason
        }

    }
}
