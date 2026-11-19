/*
 * ReservationCancellationControllerTest.kt
 *
 * Purpose: Verify cancellation consent, retries, lifecycle checks, and completed receipts.
 * Role: Local JVM controller tests with a fake owned reservation repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

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
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises requests and delayed worker outcomes without Firebase or a real clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationCancellationControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val original = ReservationEntry(Reservation("booking", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(100, 0), Timestamp(200, 0), 2, 6550, true, "confirmed"))
    private val completed = original.copy(reservation = original.reservation.copy(status = "cancelled", updatedAt = Timestamp(170, 0)))
    private val repository = FakeReservations()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun openingAndDismissingConsentNeverSubmitARequest() = runTest(dispatcher) {
        val controller = controller()
        runCurrent()
        controller.requestCancel()
        assertTrue(controller.state.value.confirmingCancellation)
        assertSame(controller, controller())
        controller.dismissCancellation()
        controller.confirmCancellation()
        runCurrent()
        assertFalse(controller.state.value.confirmingCancellation)
        assertEquals(0, repository.requests)
    }

    @Test fun repeatedConfirmationSubmitsOnceAndPendingIsNotCompleted() = runTest(dispatcher) {
        val pending = CompletableDeferred<ReservationEntry>()
        repository.submit = { pending.await() }
        val controller = controller()
        runCurrent()
        controller.requestCancel()
        controller.confirmCancellation()
        controller.confirmCancellation()
        controller.requestCancel()
        runCurrent()
        assertEquals(1, repository.requests)
        assertTrue(controller.state.value.requestingCancellation)
        pending.complete(original.copy(reservation = original.reservation.copy(cancellationRequested = true)))
        runCurrent()
        assertTrue(controller.state.value.entry!!.reservation.cancellationRequested)
        assertFalse(controller.state.value.entry!!.reservation.cancellationCompleted())
        controller.exportCancellation { fail("A pending request is not a cancellation receipt") }
        runCurrent()
        controller.requestCancel()
        assertFalse(controller.state.value.confirmingCancellation)
    }

    @Test fun statusChecksStopWhenHiddenAndShowCompletionOnlyAfterTheWorkerChangesTheRecord() = runTest(dispatcher) {
        repository.saved = original.copy(reservation = original.reservation.copy(cancellationRequested = true))
        val controller = controller()
        controller.resume()
        runCurrent()
        assertEquals(1, repository.reads)
        advanceTimeBy(5_000); runCurrent()
        assertFalse(controller.state.value.entry!!.reservation.cancellationCompleted())
        controller.pause()
        val reads = repository.reads
        repository.saved = completed
        advanceTimeBy(20_000); runCurrent()
        assertEquals(reads, repository.reads)
        controller.resume()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(completed, controller.state.value.entry)
        val finalReads = repository.reads
        advanceTimeBy(20_000); runCurrent()
        assertEquals(finalReads, repository.reads)
        var csv: String? = null
        controller.exportCancellation { csv = it }
        runCurrent()
        assertTrue(checkNotNull(csv).contains("\"cancelled\""))
        assertEquals("Cancellation CSV saved.", controller.state.value.exportNotice)
    }

    @Test fun failedStatusChecksRemainPendingAndRefreshCanRecover() = runTest(dispatcher) {
        repository.saved = original.copy(reservation = original.reservation.copy(cancellationRequested = true))
        val controller = controller()
        controller.resume()
        runCurrent()
        repository.failReads = true
        advanceTimeBy(5_000); runCurrent()
        assertNotNull(controller.state.value.cancellationNotice)
        assertFalse(controller.state.value.entry!!.reservation.cancellationCompleted())
        repository.failReads = false
        repository.saved = completed
        controller.retry()
        runCurrent()
        assertEquals(completed, controller.state.value.entry)
        assertNull(controller.state.value.cancellationNotice)
    }

    @Test fun uncertainRequestFailureDoesNotClaimSuccessOrAutomaticallyResubmit() = runTest(dispatcher) {
        repository.submit = {
            repository.saved = original.copy(reservation = original.reservation.copy(cancellationRequested = true))
            error("Response lost after commit")
        }
        val controller = controller()
        runCurrent()
        controller.requestCancel(); controller.confirmCancellation(); runCurrent()
        assertNotNull(controller.state.value.cancellationNotice)
        assertFalse(controller.state.value.requestingCancellation)
        assertFalse(controller.state.value.entry!!.reservation.cancellationCompleted())
        controller.retry(); runCurrent()
        assertTrue(controller.state.value.entry!!.reservation.cancellationRequested)
        assertEquals(1, repository.requests)
    }

    @Test fun pastAndTerminalReservationsDoNotOpenTheCancellationDialog() = runTest(dispatcher) {
        for (entry in listOf(completed, original.copy(reservation = original.reservation.copy(status = "rejected")),
            original.copy(reservation = original.reservation.copy(endDate = Timestamp(150, 0))))) {
            repository.saved = entry
            val controller = controller()
            controller.retry(); runCurrent()
            controller.requestCancel()
            assertFalse(controller.state.value.confirmingCancellation)
        }
        assertEquals(0, repository.requests)
    }

    /** Creates a navigation-entry-scoped controller with a fixed time before checkout. */
    private fun controller(): ReservationDetailsController = ViewModelProvider.create(store, viewModelFactory {
        initializer { ReservationDetailsController("booking", repository) { Timestamp(150, 0) } }
    })[ReservationDetailsController::class]

    /** Controls read and request outcomes independently, including uncertain request commits. */
    private inner class FakeReservations : ReservationRepository {
        var saved: ReservationEntry? = original
        var reads = 0
        var requests = 0
        var failReads = false
        var submit: suspend () -> ReservationEntry = {
            checkNotNull(saved).copy(reservation = checkNotNull(saved).reservation.copy(cancellationRequested = true)).also { saved = it }
        }
        override suspend fun getMyReservations(): List<ReservationEntry> = error("Unexpected list read")
        override suspend fun getReservation(reservationId: String): ReservationEntry? {
            reads++
            if (failReads) error("Unavailable")
            return saved
        }
        override suspend fun requestCancellation(reservationId: String): ReservationEntry { requests++; return submit() }
    }
}
