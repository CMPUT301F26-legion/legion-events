/*
 * ReservationsControllerTest.kt
 *
 * Purpose: Verify stay periods, tab restoration, checkout, retries, and request cancellation.
 * Role: Local JVM tests for the reservations controller using synthetic booking data.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/** Tests stay-period decisions and request lifecycle using synthetic owned bookings. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val at = 2_000_000_000L

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun activeIncludesUpcomingAndOngoingConfirmedStaysOnlyInCheckInOrder() = runTest(dispatcher) {
        val entries = listOf(
            entry("later", at + 10, at + 30), entry("ongoing", at - 10, at + 10),
            entry("checked-out", at - 20, at), entry("past", at - 40, at - 20),
            entry("cancelled", at + 1, at + 2, "cancelled"), entry("rejected", at + 1, at + 2, "rejected"),
            entry("pending", at + 1, at + 2, "pending"),
        )
        val controller = controller(FakeReservations { entries })
        controller.refresh()
        runCurrent()
        assertEquals(listOf("ongoing", "later"), controller.state.value.entries.map { it.reservation.id })
        assertFalse(controller.state.value.loading)
    }

    @Test fun confirmedReservationRemainsActiveWhileCancellationIsOnlyRequested() {
        val reservation = entry("pending-cancellation", at - 5, at + 5).reservation.copy(cancellationRequested = true)
        assertTrue(reservation.isActive(Timestamp(at, 0)))
        assertFalse(reservation.isActive(reservation.endDate))
        assertFalse(reservation.copy(status = "cancelled").isActive(Timestamp(at, 0)))
    }

    @Test fun pastIncludesOnlyFinishedConfirmedStaysInLatestCheckoutOrder() = runTest(dispatcher) {
        val entries = listOf(
            entry("older", at - 40, at - 20), entry("b", at - 20, at), entry("a", at - 10, at),
            entry("ongoing", at - 10, at + 10), entry("upcoming", at + 10, at + 20),
            entry("cancelled", at - 20, at - 10, "cancelled"),
            entry("rejected", at - 20, at - 10, "rejected"),
            entry("pending", at - 20, at - 10, "pending"),
            entry("unknown", at - 20, at - 10, "unknown"),
        )
        val controller = controller(FakeReservations { entries })
        controller.refresh()
        runCurrent()
        assertEquals(listOf("a", "b", "older"), controller.state.value.pastEntries.map { it.reservation.id })
        assertEquals(listOf("ongoing", "upcoming"), controller.state.value.entries.map { it.reservation.id })
    }

    @Test fun pastStartsExactlyAtCheckoutWithoutChangingTheSavedStatus() {
        val reservation = entry("stay", at - 10, at).reservation.copy(cancellationRequested = true)
        assertFalse(reservation.isPast(Timestamp(at - 1, 999_999_999)))
        assertTrue(reservation.isPast(Timestamp(at, 0)))
        assertFalse(reservation.isActive(Timestamp(at, 0)))
        assertEquals("confirmed", reservation.status)
        assertFalse(reservation.copy(status = "cancelled").isPast(Timestamp(at, 0)))
    }

    @Test fun selectingPastSurvivesRefreshFailureRetryAndControllerRestoration() = runTest(dispatcher) {
        var reads = 0
        var fail = false
        val repository = FakeReservations {
            reads++
            if (fail) error("Unavailable")
            listOf(entry("past", at - 20, at - 10))
        }
        val savedState = SavedStateHandle()
        val controller = controller(repository, savedState)
        controller.refresh()
        runCurrent()
        controller.selectTab(ReservationTab.PAST)
        assertEquals(1, reads)
        assertEquals(ReservationTab.PAST, controller.state.value.selectedTab)
        fail = true
        controller.refresh()
        assertEquals(ReservationTab.PAST, controller.state.value.selectedTab)
        assertTrue(controller.state.value.pastEntries.isEmpty())
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertEquals(ReservationTab.PAST, controller.state.value.selectedTab)
        fail = false
        controller.refresh()
        runCurrent()
        assertEquals("past", controller.state.value.pastEntries.single().reservation.id)
        assertEquals(ReservationTab.PAST, controller.state.value.selectedTab)
        val restoredState = SavedStateHandle(mapOf("reservationTab" to savedState.get<String>("reservationTab")))
        store.clear()
        val restored = controller(repository, restoredState)
        assertEquals(ReservationTab.PAST, restored.state.value.selectedTab)
        assertTrue(restored.state.value.pastEntries.isEmpty())
        restored.refresh()
        runCurrent()
        assertEquals("past", restored.state.value.pastEntries.single().reservation.id)
    }

    @Test fun loadingAndFailureDoNotExposeStaleEntriesAndRetryRecovers() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<ReservationEntry>>()
        var load: suspend () -> List<ReservationEntry> = { pending.await() }
        val controller = controller(FakeReservations { load() })
        controller.refresh()
        runCurrent()
        assertTrue(controller.state.value.loading)
        assertTrue(controller.state.value.entries.isEmpty())
        pending.complete(listOf(entry("upcoming", at + 10, at + 20)))
        runCurrent()
        assertEquals(1, controller.state.value.entries.size)
        load = { error("Unavailable") }
        controller.refresh()
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertTrue(controller.state.value.entries.isEmpty())
        load = { emptyList() }
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.entries.isEmpty())
    }

    @Test fun checkoutMovesAStayToPastWithoutAnotherDatabaseRequest() = runTest(dispatcher) {
        var reads = 0
        val controller = controller(FakeReservations { reads++; listOf(entry("ending", at - 1, at + 1)) })
        controller.refresh()
        runCurrent()
        assertEquals(1, controller.state.value.entries.size)
        assertTrue(controller.state.value.pastEntries.isEmpty())
        controller.selectTab(ReservationTab.PAST)
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(controller.state.value.entries.isEmpty())
        assertEquals("ending", controller.state.value.pastEntries.single().reservation.id)
        assertEquals(ReservationTab.PAST, controller.state.value.selectedTab)
        assertEquals(1, reads)
    }

    @Test fun refreshAndLeavingTheScreenCancelPreviousRequests() = runTest(dispatcher) {
        var active = 0
        val controller = controller(FakeReservations {
            active++
            try { awaitCancellation() } finally { active-- }
        })
        controller.refresh()
        runCurrent()
        repeat(4) { controller.refresh() }
        runCurrent()
        assertEquals(1, active)
        store.clear()
        runCurrent()
        assertEquals(0, active)
    }

    /** Creates a lifecycle-owned controller with a clock that follows coroutine test time. */
    private fun controller(repository: ReservationRepository, savedState: SavedStateHandle = SavedStateHandle()): ReservationsController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { ReservationsController(repository, savedState) { Timestamp(Date(at * 1000 + dispatcher.scheduler.currentTime)) } }
        })[ReservationsController::class]

    /** Builds a saved booking independently of the device's date. */
    private fun entry(id: String, start: Long, end: Long, status: String = "confirmed") = ReservationEntry(
        Reservation(id, "lottery", "offer", "pine", "alice", "owner", Timestamp(start, 0), Timestamp(end, 0),
            4, 6550, true, status))

    /** Supplies list responses without contacting Firebase. */
    private class FakeReservations(private val load: suspend () -> List<ReservationEntry>) : ReservationRepository {
        override suspend fun getMyReservations() = load()
        override suspend fun getReservation(reservationId: String): ReservationEntry? = error("Unexpected detail read")
    }
}
