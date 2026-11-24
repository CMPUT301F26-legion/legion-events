/*
 * RateStayControllerTest.kt
 *
 * Purpose: Verify rating loading, draft restoration, explicit saves, and retry handling.
 * Role: Local JVM controller tests with synthetic stays and an in-memory rating repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.RatingDetails
import com.example.legion_events.data.RatingRepository
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.model.Rating
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

/** Exercises the lifecycle-owned controller without network calls or a real clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class RateStayControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val stay = ReservationEntry(Reservation("booking", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(100, 0), Timestamp(200, 0), 2, 6550, true, "confirmed"))
    private val repository = FakeRatings()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun loadWaitsForEligibilityAndNeverDefaultsOrSubmitsAnUnselectedScore() = runTest(dispatcher) {
        val pending = CompletableDeferred<RatingDetails?>()
        repository.load = { pending.await() }
        val controller = controller()
        runCurrent()
        controller.selectScore(5); controller.submit()
        assertTrue(controller.state.value.loading)
        assertNull(controller.state.value.score)
        pending.complete(RatingDetails(stay, null)); runCurrent()
        controller.submit(); runCurrent()
        assertEquals(0, repository.writes)
        assertNull(controller.state.value.score)
    }

    @Test fun anExistingCampsiteRatingIsPrefilledAndCanBeUpdated() = runTest(dispatcher) {
        repository.load = { RatingDetails(stay, Rating("pine", "alice", 2)) }
        val controller = controller()
        runCurrent()
        assertEquals(2, controller.state.value.score)
        controller.selectScore(4); controller.submit(); runCurrent()
        assertEquals(4, repository.savedScore)
        assertTrue(controller.state.value.saved)
    }

    @Test fun aDraftSurvivesControllerRestorationWithoutSubmittingAnything() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val controller = controller(savedState)
        runCurrent()
        controller.selectScore(3)
        assertSame(controller, controller(savedState))
        val restoredState = SavedStateHandle(mapOf("ratingScore" to savedState.get<Int>("ratingScore")))
        store.clear()
        val restored = controller(restoredState)
        runCurrent()
        assertEquals(3, restored.state.value.score)
        assertFalse(restored.state.value.saved)
        assertEquals(0, repository.writes)
    }

    @Test fun loadingFailureAndUnavailableStaysHaveNoSaveActionAndRetryRecovers() = runTest(dispatcher) {
        repository.load = { error("Unavailable") }
        val controller = controller()
        runCurrent()
        assertNotNull(controller.state.value.error)
        repository.load = { null }
        controller.retry(); runCurrent()
        controller.selectScore(5); controller.submit(); runCurrent()
        assertNull(controller.state.value.details)
        assertEquals(0, repository.writes)
        repository.load = { RatingDetails(stay, null) }
        controller.retry(); runCurrent()
        assertNotNull(controller.state.value.details)
    }

    @Test fun duplicateSubmitsAreIgnoredAndFailuresKeepTheScoreForRetry() = runTest(dispatcher) {
        val pending = CompletableDeferred<Rating>()
        repository.save = { pending.await() }
        val controller = controller()
        runCurrent()
        controller.selectScore(4); controller.submit(); controller.submit(); runCurrent()
        controller.selectScore(1); controller.retry()
        assertTrue(controller.state.value.saving)
        assertEquals(4, controller.state.value.score)
        assertEquals(1, repository.writes)
        pending.completeExceptionally(SecurityException("Access changed")); runCurrent()
        assertNotNull(controller.state.value.saveError)
        assertFalse(controller.state.value.saved)
        repository.save = { Rating("pine", "alice", it) }
        controller.submit(); runCurrent()
        assertTrue(controller.state.value.saved)
        controller.submit(); runCurrent()
        assertEquals(2, repository.writes)
    }

    @Test fun scoreRangeAndCheckoutEligibilityMatchTheModel() {
        for (score in listOf(0, 6)) assertThrows(IllegalArgumentException::class.java) { Rating("pine", "alice", score) }
        assertFalse(stay.reservation.canRate(Timestamp(199, 999_999_999)))
        assertTrue(stay.reservation.canRate(Timestamp(200, 0)))
        assertFalse(stay.reservation.copy(cancellationRequested = true).canRate(Timestamp(200, 0)))
        for (status in listOf("cancelled", "rejected", "requested", "unknown")) {
            assertFalse(stay.reservation.copy(status = status).canRate(Timestamp(200, 0)))
        }
    }

    /** Creates the controller in a real ViewModelStore with a test-restorable draft. */
    private fun controller(savedState: SavedStateHandle = SavedStateHandle()): RateStayController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { RateStayController("booking", repository, savedState) }
        })[RateStayController::class]

    /** Controls load and save results independently and counts explicit submits. */
    private inner class FakeRatings : RatingRepository {
        var load: suspend () -> RatingDetails? = { RatingDetails(stay, null) }
        var save: suspend (Int) -> Rating = { Rating("pine", "alice", it) }
        var writes = 0
        var savedScore: Int? = null
        override suspend fun getForStay(reservationId: String): RatingDetails? { assertEquals("booking", reservationId); return load() }
        override suspend fun saveForStay(reservationId: String, score: Int): Rating {
            assertEquals("booking", reservationId); writes++; savedScore = score; return save(score)
        }
    }
}
