/* Tests foreground deadline changes, retry, and lifecycle cancellation with synthetic lotteries. */
package com.example.legion_events.controller

import com.example.legion_events.data.LocalLotteryDrawRepository
import com.example.legion_events.data.LocalLotteryDrawCandidate
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Verifies the local trigger without Firebase or a running Android activity. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalLotteryDrawControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Timestamp(100, 0)
    private val repository = FakeDraws()
    private lateinit var controller: LocalLotteryDrawController

    /** Provides a controlled main dispatcher and fixed deadline clock. */
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = LocalLotteryDrawController(repository) { clock }
    }

    /** Cancels background checks before restoring the Android main dispatcher. */
    @After fun tearDown() {
        controller.stop()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    /** An initial check skips a future deadline and a saved past deadline triggers immediately. */
    @Test fun savedDeadlineChangeDrawsWithoutWaitingForTimer() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 101))
        controller.start("owner")
        runCurrent()
        assertTrue(repository.attempts.isEmpty())
        repository.lotteries.value = listOf(lottery("pine", 99))
        runCurrent()
        assertEquals(listOf("pine"), repository.attempts)
        assertEquals(1, controller.completedDraws.value)
        assertEquals("owner", repository.observedOwner)
    }

    /** A clock check can discover an elapsed deadline without any Firestore change event. */
    @Test fun clockChecksCatchDeadlinesThatPassNaturally() = runForegroundTest {
        var time = Timestamp(100, 0)
        controller = LocalLotteryDrawController(repository) { time }
        repository.lotteries.value = listOf(lottery("pine", 101))
        controller.start("owner")
        runCurrent()
        time = Timestamp(101, 0)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("pine"), repository.attempts)
    }

    /** One failing draw remains visible while another lottery can complete and the failure later retries. */
    @Test fun failureDoesNotBlockAnotherLotteryAndTimerRetries() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("broken", 99), lottery("ready", 99))
        repository.failedIds = setOf("broken")
        controller.start("owner")
        runCurrent()
        assertEquals(listOf("broken", "ready"), repository.attempts)
        assertNotNull(controller.error.value)
        assertEquals(1, controller.completedDraws.value)
        repository.failedIds = emptySet()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, controller.completedDraws.value)
        assertNull(controller.error.value)
    }

    /** Hiding the app stops checks, and returning catches the missed deadline once. */
    @Test fun pauseStopsChecksAndResumeCatchesUp() = runForegroundTest {
        controller.start("owner")
        runCurrent()
        controller.stop()
        repository.lotteries.value = listOf(lottery("pine", 99))
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(repository.attempts.isEmpty())
        controller.start("owner")
        runCurrent()
        assertEquals(listOf("pine"), repository.attempts)
    }

    /** Listener failures are surfaced and a new subscription is established automatically. */
    @Test fun listenerFailureReconnectsAndDraws() = runForegroundTest {
        repository.failListener = true
        repository.lotteries.value = listOf(lottery("pine", 99))
        controller.start("owner")
        runCurrent()
        assertNotNull(controller.error.value)
        repository.failListener = false
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("pine"), repository.attempts)
        assertNull(controller.error.value)
    }

    /** A malformed deadline reaches the individual draw error path while valid lotteries still complete. */
    @Test fun malformedCandidateDoesNotHideOtherDueLotteries() = runForegroundTest {
        repository.lotteries.value = listOf(LocalLotteryDrawCandidate("malformed", null), lottery("ready", 99))
        repository.failedIds = setOf("malformed")
        controller.start("owner")
        runCurrent()
        assertEquals(listOf("malformed", "ready"), repository.attempts)
        assertEquals(1, controller.completedDraws.value)
        assertNotNull(controller.error.value)
    }

    /** Stops the foreground timer before runTest drains its scheduler, including after failed assertions. */
    private fun runForegroundTest(test: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { test() } finally { controller.stop() }
    }

    /** Supplies a saved candidate deadline for the foreground eligibility check. */
    private fun lottery(id: String, endSeconds: Long) = LocalLotteryDrawCandidate(id, Timestamp(endSeconds, 0))

    /** Tracks subscriptions and successful draws without relying on a backend. */
    private class FakeDraws : LocalLotteryDrawRepository {
        val lotteries = MutableStateFlow<List<LocalLotteryDrawCandidate>>(emptyList())
        val attempts = mutableListOf<String>()
        private val completed = mutableSetOf<String>()
        var failedIds = emptySet<String>()
        var failListener = false
        var observedOwner: String? = null

        /** Produces the current owner list or the configured listener failure. */
        override fun observeOwnedLotteries(ownerId: String) = flow {
            observedOwner = ownerId
            if (failListener) error("Listener failed")
            lotteries.collect { emit(it) }
        }

        /** Simulates once-only commits and isolated failures for selected lottery IDs. */
        override suspend fun draw(lotteryId: String): Boolean {
            attempts += lotteryId
            if (lotteryId in failedIds) error("Draw failed")
            return completed.add(lotteryId)
        }
    }
}
