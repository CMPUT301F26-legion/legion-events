/* Offer state tests use fake repository responses, not Firebase users or documents. */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.model.BookingOffer
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

/** Verifies confirmed declines, expiry, failure recovery, and ViewModel cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookingOfferControllerTest {
    /** Dispatcher used for both ViewModel requests and assertions. */
    private val dispatcher = StandardTestDispatcher()
    /** Owns controllers so clearing the store exercises real lifecycle cancellation. */
    private val store = ViewModelStore()
    /** Mutable clock permits expiry while confirmation is open. */
    private var time = Timestamp(100, 0)
    /** Synthetic offer owned by the fake signed-in entrant. */
    private val open = BookingOffer("offer", "pine", "alice", expiresAt = Timestamp(200, 0))

    /** Installs the controlled dispatcher before constructing ViewModels. */
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    /** Cancels remaining work and restores the platform dispatcher. */
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    /** Loading remains visible until the repository supplies the selected offer. */
    @Test fun pendingReadBecomesOwnedOffer() = runTest(dispatcher) {
        val pending = CompletableDeferred<BookingOffer?>()
        val controller = controller(FakeOffers(read = { pending.await() }))
        runCurrent()
        assertTrue(controller.state.value.loading)
        pending.complete(open)
        runCurrent()
        assertEquals(open, controller.state.value.offer)
        assertTrue(controller.state.value.canDecline)
    }

    /** Missing and inaccessible offers expose no usable decline action. */
    @Test fun missingAndForeignOffersAreUnavailable() = runTest(dispatcher) {
        val repository = FakeOffers(read = { null })
        val controller = controller(repository)
        runCurrent()
        assertNull(controller.state.value.offer)
        assertFalse(controller.state.value.canDecline)
        repository.read = { throw SecurityException("Private UID details") }
        controller.retry()
        runCurrent()
        assertEquals("This offer is unavailable for your account.", controller.state.value.error)
        assertNull(controller.state.value.offer)
    }

    /** Read failures can be retried without leaking repository exception details. */
    @Test fun readFailureCanBeRetried() = runTest(dispatcher) {
        val repository = FakeOffers(read = { error("Internal details") })
        val controller = controller(repository)
        runCurrent()
        assertNotNull(controller.state.value.error)
        repository.read = { open }
        controller.retry()
        runCurrent()
        assertEquals(open, controller.state.value.offer)
        assertNull(controller.state.value.error)
    }

    /** Confirmation and cancellation do not write, and confirmation cannot be bypassed. */
    @Test fun declineRequiresConfirmationAndCancelDoesNotWrite() = runTest(dispatcher) {
        val repository = FakeOffers()
        val controller = controller(repository)
        runCurrent()
        controller.confirmDecline()
        controller.requestDecline()
        assertTrue(controller.state.value.confirming)
        controller.cancelDecline()
        runCurrent()
        assertFalse(controller.state.value.confirming)
        assertEquals(0, repository.declineCalls)
    }

    /** Repeated clicks and reloads cannot start duplicate work during an in-flight decline. */
    @Test fun confirmedDeclineBlocksDuplicateClicksAndDisplaysSavedResult() = runTest(dispatcher) {
        val pending = CompletableDeferred<BookingOffer>()
        val repository = FakeOffers(decline = { pending.await() })
        val controller = controller(repository)
        runCurrent()
        controller.requestDecline()
        repeat(3) { controller.confirmDecline(); controller.requestDecline(); controller.retry() }
        runCurrent()
        assertTrue(controller.state.value.declining)
        assertFalse(controller.state.value.canDecline)
        assertEquals(1, repository.declineCalls)
        assertEquals(1, repository.readCalls)
        val saved = open.copy(status = BookingOffer.STATUS_DECLINED, updatedAt = Timestamp(150, 0))
        pending.complete(saved)
        runCurrent()
        assertEquals(saved, controller.state.value.offer)
        assertFalse(controller.state.value.declining)
        assertFalse(controller.state.value.canDecline)
    }

    /** Deadline equality is expired, both at initial read and during an open confirmation. */
    @Test fun expiryIsRecheckedAtConfirmation() = runTest(dispatcher) {
        val repository = FakeOffers()
        val controller = controller(repository)
        runCurrent()
        controller.requestDecline()
        time = Timestamp(200, 0)
        controller.confirmDecline()
        runCurrent()
        assertTrue(controller.state.value.expired)
        assertFalse(controller.state.value.confirming)
        assertEquals(0, repository.declineCalls)
        controller.retry()
        runCurrent()
        assertFalse(controller.state.value.canDecline)
    }

    /** Used, declined, and expired statuses never reach the decline repository operation. */
    @Test fun terminalOffersCannotBeDeclined() = runTest(dispatcher) {
        val repository = FakeOffers()
        val controller = controller(repository)
        for (status in listOf(BookingOffer.STATUS_USED, BookingOffer.STATUS_DECLINED, BookingOffer.STATUS_EXPIRED)) {
            repository.read = { open.copy(status = status) }
            controller.retry()
            runCurrent()
            controller.requestDecline()
            controller.confirmDecline()
            assertFalse(controller.state.value.canDecline)
        }
        assertEquals(0, repository.declineCalls)
    }

    /** A server response lost after commit can be retried using the same stable offer ID. */
    @Test fun uncertainDeclineCanBeRetriedWithoutLosingLoadedOffer() = runTest(dispatcher) {
        val repository = FakeOffers(decline = { error("Lost response") })
        val controller = controller(repository)
        runCurrent()
        controller.requestDecline()
        controller.confirmDecline()
        runCurrent()
        assertEquals(open, controller.state.value.offer)
        assertNotNull(controller.state.value.actionError)
        repository.decline = { open.copy(status = BookingOffer.STATUS_DECLINED) }
        controller.requestDecline()
        controller.confirmDecline()
        runCurrent()
        assertEquals(BookingOffer.STATUS_DECLINED, controller.state.value.offer?.status)
        assertNull(controller.state.value.actionError)
        assertEquals(2, repository.declineCalls)
    }

    /** Ownership denial during the action removes the previously loaded offer from the view. */
    @Test fun deniedDeclineClearsOffer() = runTest(dispatcher) {
        val controller = controller(FakeOffers(decline = { throw SecurityException("Denied") }))
        runCurrent()
        controller.requestDecline()
        controller.confirmDecline()
        runCurrent()
        assertNull(controller.state.value.offer)
        assertNotNull(controller.state.value.error)
    }

    /** Replacing a read and clearing the owner cancel requests instead of reporting errors. */
    @Test fun leavingScreenCancelsReadAndDecline() = runTest(dispatcher) {
        var active = 0
        val repository = FakeOffers(read = {
            active++
            try { awaitCancellation() } finally { active-- }
        })
        val controller = controller(repository)
        runCurrent()
        repeat(3) { controller.retry() }
        runCurrent()
        assertEquals(1, active)
        repository.read = { open }
        repository.decline = {
            active++
            try { awaitCancellation() } finally { active-- }
        }
        controller.retry()
        runCurrent()
        controller.requestDecline()
        controller.confirmDecline()
        runCurrent()
        assertEquals(1, active)
        store.clear()
        runCurrent()
        assertEquals(0, active)
    }

    /** Creates a lifecycle-owned controller with the fake clock. */
    private fun controller(repository: OfferRepository): BookingOfferController = ViewModelProvider.create(store,
        viewModelFactory { initializer { BookingOfferController("offer", repository) { time } } })[BookingOfferController::class]

    /** Configurable responses and call counters, with no Firebase connection. */
    private inner class FakeOffers(
        var read: suspend () -> BookingOffer? = { open },
        var decline: suspend () -> BookingOffer = { open.copy(status = BookingOffer.STATUS_DECLINED) },
    ) : OfferRepository {
        /** Number of detail reads made by the controller. */
        var readCalls = 0
        /** Number of confirmed decline attempts. */
        var declineCalls = 0
        /** Verifies the routed ID before returning the configured response. */
        override suspend fun getOffer(offerId: String): BookingOffer? {
            assertEquals("offer", offerId)
            readCalls++
            return read()
        }
        /** Not used by a single-offer controller. */
        override suspend fun getOffersForCurrentUser(): List<BookingOffer> = error("Unexpected list read")
        /** Verifies the selected document before returning or failing the action. */
        override suspend fun declineOffer(offerId: String): BookingOffer {
            assertEquals("offer", offerId)
            declineCalls++
            return decline()
        }
    }
}
