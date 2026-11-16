/*
 * ReservationDetailsControllerTest.kt
 *
 * Purpose: Verify owned detail reads and confirmation exports, failures, and retries.
 * Role: Local JVM tests for the reservation details controller using a fake repository.
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

/** Tests S10b loading, ownership-denial recovery, and missing records with no database access. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val entry = ReservationEntry(Reservation("booking", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(100, 0), Timestamp(200, 0), 2, 6550, true, "confirmed"))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun waitsForTheOwnedRecordAndKeepsItAcrossControllerLookup() = runTest(dispatcher) {
        val pending = CompletableDeferred<ReservationEntry?>()
        var reads = 0
        val repository = repository { reads++; pending.await() }
        val controller = controller(repository)
        runCurrent()
        assertTrue(controller.state.value.loading)
        assertNull(controller.state.value.entry)
        pending.complete(entry)
        runCurrent()
        assertEquals(entry, controller.state.value.entry)
        assertSame(controller, controller(repository))
        assertEquals(1, reads)
    }

    @Test fun denialAndRetryNeverRetainAnotherRecordsInformation() = runTest(dispatcher) {
        var denied = true
        val controller = controller(repository { if (denied) throw SecurityException("Not owned") else entry })
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.entry)
        denied = false
        controller.retry()
        runCurrent()
        assertEquals(entry, controller.state.value.entry)
        assertNull(controller.state.value.error)
    }

    @Test fun missingReservationHasNoBookingDetailsOrRequestError() = runTest(dispatcher) {
        val controller = controller(repository { null })
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.entry)
        assertNull(controller.state.value.error)
    }

    @Test fun exportRechecksTheOwnedRecordAndUsesItsLatestSavedValues() = runTest(dispatcher) {
        var reads = 0
        var saved = entry
        val controller = controller(repository { reads++; saved })
        runCurrent()
        saved = entry.copy(reservation = entry.reservation.copy(totalPrice = 12345))
        var csv: String? = null
        controller.exportConfirmation { csv = it }
        assertTrue(controller.state.value.exporting)
        runCurrent()
        assertEquals(2, reads)
        assertTrue(checkNotNull(csv).contains("\"123.45\""))
        assertEquals("Confirmation CSV saved.", controller.state.value.exportNotice)
        assertFalse(controller.state.value.exporting)
    }

    @Test fun loadingAndDuplicateExportRequestsDoNotWriteExtraReceipts() = runTest(dispatcher) {
        val pending = CompletableDeferred<ReservationEntry?>()
        val controller = controller(repository { pending.await() })
        var writes = 0
        controller.exportConfirmation { writes++ }
        runCurrent()
        assertEquals(0, writes)
        pending.complete(entry)
        runCurrent()
        val writing = CompletableDeferred<Unit>()
        controller.exportConfirmation { writes++; writing.await() }
        runCurrent()
        controller.exportConfirmation { writes++ }
        controller.retry()
        runCurrent()
        assertEquals(1, writes)
        assertTrue(controller.state.value.exporting)
        writing.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.exporting)
    }

    @Test fun deletedAndNewlyCancelledReservationsAreNotExported() = runTest(dispatcher) {
        var saved: ReservationEntry? = entry
        val controller = controller(repository { saved })
        runCurrent()
        saved = entry.copy(reservation = entry.reservation.copy(status = "cancelled"))
        controller.exportConfirmation { fail("A cancelled booking must not be exported") }
        runCurrent()
        assertEquals("cancelled", controller.state.value.entry?.reservation?.status)
        assertFalse(controller.state.value.exporting)
        saved = entry
        controller.retry()
        runCurrent()
        saved = null
        controller.exportConfirmation { fail("A deleted booking must not be exported") }
        runCurrent()
        assertNull(controller.state.value.entry)
        assertFalse(controller.state.value.exporting)
    }

    @Test fun accessFailuresNeverWriteAndFileFailuresCanBeRetried() = runTest(dispatcher) {
        var denied = false
        val controller = controller(repository { if (denied) throw SecurityException("Not owned") else entry })
        runCurrent()
        denied = true
        controller.exportConfirmation { fail("An inaccessible booking must not be exported") }
        runCurrent()
        assertEquals("Could not save the confirmation CSV. Try downloading again.", controller.state.value.exportNotice)
        assertFalse(controller.state.value.exporting)
        denied = false
        controller.exportConfirmation { throw java.io.IOException("No space") }
        runCurrent()
        assertFalse(controller.state.value.exporting)
        assertEquals(entry, controller.state.value.entry)
        var writes = 0
        controller.exportConfirmation { writes++ }
        runCurrent()
        assertEquals(1, writes)
        assertEquals("Confirmation CSV saved.", controller.state.value.exportNotice)
    }

    @Test fun missingSaveHandlerShowsAUsefulMessageWithoutChangingTheBooking() = runTest(dispatcher) {
        val controller = controller(repository { entry })
        runCurrent()
        controller.reportSaveUnavailable()
        assertEquals("No app can save documents on this device.", controller.state.value.exportNotice)
        assertEquals(entry, controller.state.value.entry)
    }

    /** Creates an entry-scoped controller that loads only the route's reservation ID. */
    private fun controller(repository: ReservationRepository): ReservationDetailsController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { ReservationDetailsController("booking", repository) }
        })[ReservationDetailsController::class]

    /** Supplies a controlled detail response and rejects unrelated list reads. */
    private fun repository(load: suspend () -> ReservationEntry?) = object : ReservationRepository {
        override suspend fun getMyReservations(): List<ReservationEntry> = error("Unexpected list read")
        override suspend fun getReservation(reservationId: String): ReservationEntry? {
            assertEquals("booking", reservationId)
            return load()
        }
    }
}
