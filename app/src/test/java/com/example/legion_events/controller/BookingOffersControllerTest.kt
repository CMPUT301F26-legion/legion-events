/* List controller tests use synthetic offers and delayed repository responses. */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.model.BookingOffer
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Verifies list ordering, refresh after decline, error recovery, and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookingOffersControllerTest {
    /** Controlled main dispatcher for ViewModel work. */
    private val dispatcher = StandardTestDispatcher()
    /** Owner used to cancel a controller when leaving its navigation entry. */
    private val store = ViewModelStore()
    /** Installs the test dispatcher. */
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    /** Disposes ViewModels and restores Main. */
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    /** A refresh loads persisted statuses and keeps dated offers newest first. */
    @Test fun loadsAndRefreshesAllStatusesNewestFirst() = runTest(dispatcher) {
        val old = BookingOffer("old", "pine", "alice", createdAt = Timestamp(1, 0))
        val recent = BookingOffer("new", "oak", "alice", createdAt = Timestamp(2, 0))
        val repository = FakeOffers { listOf(old, recent) }
        val controller = controller(repository)
        runCurrent()
        assertEquals(listOf(recent, old), controller.state.value.offers)
        repository.load = { listOf(old, recent.copy(status = BookingOffer.STATUS_DECLINED)) }
        controller.retry()
        runCurrent()
        assertEquals(BookingOffer.STATUS_DECLINED, controller.state.value.offers.first().status)
    }

    /** Error recovery can produce an empty list instead of manufacturing demo offers. */
    @Test fun failedListCanRetryToEmpty() = runTest(dispatcher) {
        val repository = FakeOffers { error("Offline") }
        val controller = controller(repository)
        runCurrent()
        assertNotNull(controller.state.value.error)
        repository.load = { emptyList() }
        controller.retry()
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.offers.isEmpty())
    }

    /** Reload and disposal cancel older reads, preventing lingering work. */
    @Test fun reloadAndLeavingCancelPendingReads() = runTest(dispatcher) {
        var active = 0
        val controller = controller(FakeOffers {
            active++
            try { awaitCancellation() } finally { active-- }
        })
        runCurrent()
        repeat(3) { controller.retry() }
        runCurrent()
        assertEquals(1, active)
        store.clear()
        runCurrent()
        assertEquals(0, active)
    }

    /** Builds a lifecycle-owned list controller. */
    private fun controller(repository: OfferRepository): BookingOffersController = ViewModelProvider.create(store,
        viewModelFactory { initializer { BookingOffersController(repository) } })[BookingOffersController::class]

    /** Supplies only the current user's synthetic list response. */
    private class FakeOffers(var load: suspend () -> List<BookingOffer>) : OfferRepository {
        /** Single-offer reads are outside the list's responsibility. */
        override suspend fun getOffer(offerId: String): BookingOffer? = error("Unexpected detail read")
        /** Returns the configured asynchronous list response. */
        override suspend fun getOffersForCurrentUser(): List<BookingOffer> = load()
        /** Declines are handled only by the single-offer controller. */
        override suspend fun declineOffer(offerId: String): BookingOffer = error("Unexpected decline")
    }
}
