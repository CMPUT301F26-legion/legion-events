/*
 * ReservationDetailsControllerTest.kt
 *
 * Purpose: Verify owned detail loading, missing records, and retry after access failures.
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
