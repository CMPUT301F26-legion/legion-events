/* BookingFlowControllerTest.kt: explicit review, changed policies, rollout, retries, and saved-state recovery. */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.BookingRepository
import com.example.legion_events.model.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class BookingFlowControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var clock = Timestamp(1_800_000_123L, 0)
    private val start = (clock.seconds.floorDiv(86_400) + 1) * 86_400 * 1000
    private val end = start + 2 * 86_400_000L
    private val expiry = Timestamp(clock.seconds + 86_400, 0)
    private val pine = Campsite("pine", "owner", "Pine", "Alberta", 4, 1555,
        rules = listOf("Quiet after 10pm"), cancellationPolicy = "Cancel before arrival.",
        availableNights = bookingNightKeys(Timestamp(java.util.Date(start)), Timestamp(java.util.Date(end + 86_400_000))))
    private val initial = BookingOptions(BookingOffer("draw_alice", "draw", "alice",
        expiresAt = expiry, campsiteIds = listOf("pine", "cedar")),
        Lottery("draw", "owner", listOf("pine", "cedar"), Timestamp(1, 0), Timestamp(2, 0), Timestamp(3, 0), expiry,
            2, winnersDrawn = true),
        listOf(BookingCampsite(pine, emptySet()), BookingCampsite(pine.copy(id = "cedar", name = "Cedar", maxGuests = 2), emptySet())))
    private val repository = FakeBookings()
    private var nextId = 0

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun loadingFailureAndForeignAccountExposeNoUsableChoicesOrWrites() = runTest(dispatcher) {
        repository.load = { error("Private internal error") }
        val controller = controller()
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.options)
        repository.load = { initial.copy(offer = initial.offer.copy(userId = "bob")) }
        controller.reload(); runCurrent()
        assertEquals("This booking offer is unavailable for your account.", controller.state.value.error)
        assertEquals(0, repository.attempts.size)
    }

    @Test fun selectionNeedsWholeAvailableDatesCapacityAndExplicitPolicyAcceptance() = runTest(dispatcher) {
        val controller = controller(); runCurrent()
        controller.selectCampsite("foreign")
        assertNull(controller.state.value.selection.campsiteId)
        controller.selectCampsite("pine")
        controller.selectCheckIn(start + 1)
        assertNull(controller.state.value.selection.checkInMillis)
        controller.selectCheckIn(start)
        controller.selectCheckOut(end + 2 * 86_400_000)
        assertNull(controller.state.value.selection.checkOutMillis)
        controller.selectCheckOut(end)
        controller.selectGuests(5)
        assertEquals(1, controller.state.value.selection.guests)
        controller.review(); controller.confirm(); runCurrent()
        assertNull(controller.state.value.review)
        assertTrue(repository.attempts.isEmpty())
        controller.acceptRules(true)
        assertTrue(controller.state.value.canReview)
        controller.selectGuests(2)
        assertFalse(controller.state.value.rulesAccepted)
    }

    @Test fun switchingSitesClearsDatesAndApprovalInsteadOfReusingAnotherSitesSelection() = runTest(dispatcher) {
        val controller = controller(); runCurrent(); select(controller)
        controller.selectCampsite("cedar")
        assertNull(controller.state.value.selection.checkInMillis)
        assertNull(controller.state.value.selection.checkOutMillis)
        assertFalse(controller.state.value.rulesAccepted)
        assertFalse(controller.state.value.canReview)
    }

    @Test fun reviewAndBackNeverWriteAndConfirmUsesExactlyTheReviewedSelection() = runTest(dispatcher) {
        val controller = controller(); runCurrent(); select(controller)
        controller.review(); runCurrent()
        assertEquals(3110L, controller.state.value.review!!.totalPrice)
        assertTrue(repository.attempts.isEmpty())
        controller.dismissReview()
        controller.confirm(); runCurrent()
        assertTrue(repository.attempts.isEmpty())
        controller.review(); runCurrent()
        val reviewed = controller.state.value.review!!
        controller.confirm(); controller.confirm(); runCurrent()
        assertEquals(listOf(reviewed), repository.attempts)
        assertEquals(Reservation.STATUS_REQUESTED, controller.state.value.result!!.status)
        assertNull(controller.state.value.review)
    }

    @Test fun changedPoliciesOrPricesRequireFreshAgreementAndShowTheNewListing() = runTest(dispatcher) {
        val controller = controller(); runCurrent(); select(controller)
        repository.options = initial.copy(campsites = listOf(BookingCampsite(pine.copy(
            cancellationPolicy = "New policy", pricePerNight = 2000), emptySet())))
        controller.review(); runCurrent()
        assertNull(controller.state.value.review)
        assertFalse(controller.state.value.rulesAccepted)
        assertEquals("New policy", controller.state.value.selected!!.campsite.cancellationPolicy)
        assertNotNull(controller.state.value.message)
        controller.acceptRules(true); controller.review(); runCurrent()
        assertEquals(4000L, controller.state.value.review!!.totalPrice)
        assertEquals("New policy", controller.state.value.review!!.terms.cancellationPolicy)
    }

    @Test fun newOccupiedNightAtReviewInvalidatesTheStayWithoutSubmitting() = runTest(dispatcher) {
        val controller = controller(); runCurrent(); select(controller)
        repository.options = initial.copy(campsites = listOf(BookingCampsite(pine, setOf(pine.availableNights.first()))))
        controller.review(); runCurrent()
        assertNull(controller.state.value.review)
        assertFalse(controller.state.value.rulesAccepted)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun rolloutDisabledStillAllowsSelectionAndReviewButNoSubmission() = runTest(dispatcher) {
        val controller = controller(enabled = false); runCurrent(); select(controller)
        controller.review(); runCurrent()
        assertNotNull(controller.state.value.review)
        controller.confirm(); controller.retrySubmission(); runCurrent()
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun deadlinePassingWhileReviewIsOpenPreventsSubmission() = runTest(dispatcher) {
        val controller = controller(); runCurrent(); select(controller)
        controller.review(); runCurrent()
        clock = expiry
        controller.confirm(); runCurrent()
        assertNull(controller.state.value.review)
        assertFalse(controller.state.value.editable)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun openingTooEarlyAndTerminalOffersCannotBeEdited() = runTest(dispatcher) {
        repository.options = initial.copy(lottery = initial.lottery.copy(bookingStartAt = expiry))
        val controller = controller(); runCurrent()
        controller.selectCampsite("pine")
        assertNull(controller.state.value.selection.campsiteId)
        repository.options = initial.copy(offer = initial.offer.copy(status = BookingOffer.STATUS_DECLINED))
        controller.reload(); runCurrent(); select(controller)
        assertNull(controller.state.value.selection.campsiteId)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun duplicateClicksAndEditsAreBlockedWhileSubmissionIsInFlight() = runTest(dispatcher) {
        val pending = CompletableDeferred<Reservation>()
        repository.save = { pending.await() }
        val controller = controller(); runCurrent(); select(controller); controller.review(); runCurrent()
        val accepted = controller.state.value.review!!
        controller.confirm(); runCurrent()
        controller.confirm(); controller.retrySubmission(); controller.selectCampsite("cedar"); controller.reload()
        assertTrue(controller.state.value.submitting)
        assertEquals("pine", controller.state.value.selection.campsiteId)
        assertEquals(1, repository.attempts.size)
        pending.complete(saved(accepted)); runCurrent()
        assertEquals(Reservation.STATUS_REQUESTED, controller.state.value.result!!.status)
    }

    @Test fun uncertainNetworkOutcomeRetriesTheSameIdAndImmutableSelection() = runTest(dispatcher) {
        repository.save = { error("Connection lost") }
        val controller = controller(); runCurrent(); select(controller); controller.review(); runCurrent()
        val accepted = controller.state.value.review!!
        controller.confirm(); runCurrent()
        controller.selectGuests(3); controller.selectCampsite("cedar")
        assertEquals(accepted, controller.state.value.pendingAttempt)
        repository.save = { saved(it) }
        controller.retrySubmission(); controller.retrySubmission(); runCurrent()
        assertEquals(listOf(accepted, accepted), repository.attempts)
        assertEquals(accepted.id, controller.state.value.result!!.id)
    }

    @Test fun lostCommitResponseIsRecoveredWithoutSubmittingAgain() = runTest(dispatcher) {
        repository.save = { repository.requests = listOf(saved(it)); error("Response lost") }
        val controller = controller(); runCurrent(); select(controller); controller.review(); runCurrent()
        controller.confirm(); runCurrent()
        controller.reload(); runCurrent()
        assertEquals(1, repository.attempts.size)
        assertEquals(Reservation.STATUS_REQUESTED, controller.state.value.result!!.status)
        assertNull(controller.state.value.pendingAttempt)
    }

    @Test fun processRecreationRestoresTheFrozenAttemptWithoutAutomaticSubmission() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        repository.save = { awaitCancellation() }
        val controller = controller(savedState); runCurrent(); select(controller); controller.review(); runCurrent()
        val accepted = controller.state.value.review!!
        controller.confirm(); runCurrent()
        val snapshot = savedState.keys().associateWith { savedState.get<Any?>(it) }
        store.clear()
        val restored = controller(SavedStateHandle(snapshot)); runCurrent()
        assertEquals(accepted, restored.state.value.pendingAttempt)
        assertEquals(1, repository.attempts.size)
        repository.save = { saved(it) }
        restored.retrySubmission(); runCurrent()
        assertEquals(listOf(accepted, accepted), repository.attempts)
    }

    @Test fun draftRestoresButChangedTermsClearItsPreviousAgreement() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val controller = controller(handle); runCurrent(); select(controller)
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        store.clear()
        repository.options = initial.copy(campsites = listOf(BookingCampsite(pine.copy(rules = listOf("New rule")), emptySet())))
        val restored = controller(SavedStateHandle(snapshot)); runCurrent()
        assertEquals(start, restored.state.value.selection.checkInMillis)
        assertEquals(end, restored.state.value.selection.checkOutMillis)
        assertFalse(restored.state.value.rulesAccepted)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun savedRequestPreventsSecondAcceptanceEvenWithoutLocalDraftState() = runTest(dispatcher) {
        repository.requests = listOf(saved(BookingRequest("existing", "draw_alice", "pine",
            Timestamp(java.util.Date(start)), Timestamp(java.util.Date(end)), 1, 3110, BookingTerms.from(pine), true)))
        val controller = controller(); runCurrent()
        assertEquals("existing", controller.state.value.result!!.id)
        select(controller); controller.review(); controller.confirm(); runCurrent()
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun workerRejectionAllowsNewReviewAndUsesANewAttemptId() = runTest(dispatcher) {
        repository.save = { saved(it).copy(status = Reservation.STATUS_REJECTED).also { value -> repository.requests = listOf(value) } }
        val controller = controller(); runCurrent(); select(controller); controller.review(); runCurrent()
        val first = controller.state.value.review!!
        controller.confirm(); runCurrent()
        controller.chooseAgain(); runCurrent()
        assertFalse(controller.state.value.rulesAccepted)
        controller.acceptRules(true); controller.review(); runCurrent()
        assertNotEquals(first.id, controller.state.value.review!!.id)
        assertEquals(1, repository.attempts.size)
    }

    @Test fun validationAbortReturnsToReviewInsteadOfRetainingAnUncertainAttempt() = runTest(dispatcher) {
        repository.save = { throw IllegalArgumentException("Policies changed") }
        val controller = controller(); runCurrent(); select(controller); controller.review(); runCurrent()
        controller.confirm(); runCurrent()
        assertNull(controller.state.value.pendingAttempt)
        assertFalse(controller.state.value.rulesAccepted)
        assertNotNull(controller.state.value.message)
    }

    /** Creates a lifecycle-owned controller with a deterministic clock and request IDs. */
    private fun controller(handle: SavedStateHandle = SavedStateHandle(), enabled: Boolean = true) =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { BookingFlowController("draw_alice", "alice", repository, handle, enabled, { clock }, { "attempt-${++nextId}" }) }
        })[BookingFlowController::class]

    /** Completes one valid draft, leaving review and confirmation as separate explicit actions. */
    private fun select(controller: BookingFlowController) {
        controller.selectCampsite("pine"); controller.selectCheckIn(start); controller.selectCheckOut(end); controller.acceptRules(true)
    }

    /** Converts the explicitly accepted details into a synthetic server-owned request. */
    private fun saved(request: BookingRequest) = Reservation(request.id, "draw", request.offerId, request.campsiteId,
        "alice", "owner", request.startDate, request.endDate, request.guestCount, request.totalPrice, true, Reservation.STATUS_REQUESTED)

    /** Server reads and writes are separately controllable; no Firebase account or network is used. */
    private inner class FakeBookings : BookingRepository {
        var options = initial
        var requests = emptyList<Reservation>()
        val attempts = mutableListOf<BookingRequest>()
        var load: suspend () -> BookingOptions = { options }
        var save: suspend (BookingRequest) -> Reservation = { saved(it).also { value -> requests = listOf(value) } }
        override suspend fun getBookingOptions(offerId: String) = load()
        override suspend fun getRequestsForOffer(offerId: String) = requests
        override suspend fun acceptOffer(request: BookingRequest): Reservation { attempts += request; return save(request) }
    }
}
