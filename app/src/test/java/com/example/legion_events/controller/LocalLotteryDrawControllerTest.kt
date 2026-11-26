/* Tests foreground deadline changes, retry, and lifecycle cancellation with synthetic lotteries. */
package com.example.legion_events.controller

import com.example.legion_events.data.LocalLotteryDrawRepository
import com.example.legion_events.data.LocalLotteryDrawCandidate
import com.example.legion_events.data.LocalLotteryDrawException
import com.example.legion_events.data.LocalLotteryDrawFailureKind.INVALID
import com.example.legion_events.data.LocalLotteryDrawFailureKind.PERMISSION_DENIED
import com.example.legion_events.data.LocalLotteryDrawFailureKind.RETRYABLE
import com.example.legion_events.data.LotteryWaitlistChangedException
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
    private val logged = mutableListOf<String>()
    private lateinit var controller: LocalLotteryDrawController

    /** Provides a controlled main dispatcher and fixed deadline clock. */
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = controller { clock }
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
        controller = controller { time }
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

    /** A data error is logged once with its lottery ID and not retried on every five-second check. */
    @Test fun permanentFailureIsNotRetriedEveryCheck() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(INVALID, IllegalStateException("The lottery has an invalid winner count or waiting list limit."))
        controller.start("owner")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("pine"), repository.attempts)
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("pine"))
        val message = controller.error.value!!
        assertTrue(message, message.contains("\"Pine Meadow\""))
        assertTrue(message, message.contains("invalid winner count"))
        assertTrue(message, message.contains("will not retry"))
    }

    /** Editing a permanently failed lottery (a new saved version) allows one fresh attempt. */
    @Test fun permanentFailureRetriesAfterTheLotteryChanges() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(INVALID, IllegalStateException("Invalid"))
        controller.start("owner")
        runCurrent()
        repository.failures.clear()
        repository.lotteries.value = listOf(lottery("pine", 99, updated = 50))
        runCurrent()
        assertEquals(listOf("pine", "pine"), repository.attempts)
        assertEquals(1, controller.completedDraws.value)
        assertNull(controller.error.value)
    }

    /** Retry attempts a permanently failed lottery again immediately. */
    @Test fun retryAttemptsAPermanentFailureAgain() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(PERMISSION_DENIED, Exception("denied"))
        controller.start("owner")
        runCurrent()
        assertTrue(controller.error.value!!.contains("permission is missing"))
        repository.failures.clear()
        controller.retry()
        runCurrent()
        assertEquals(listOf("pine", "pine"), repository.attempts)
        assertNull(controller.error.value)
    }

    /** Offline server reads keep retrying on each check, and every failure is logged with the ID. */
    @Test fun networkFailureRetriesEachCheck() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(RETRYABLE, Exception("offline"))
        controller.start("owner")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, repository.attempts.size)
        assertEquals(3, logged.count { "pine" in it })
        assertTrue(controller.error.value!!.contains("will retry automatically"))
        repository.failures.clear()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, controller.completedDraws.value)
        assertNull(controller.error.value)
    }

    /** A concurrent join or leave is retried rather than treated as invalid data. */
    @Test fun waitlistChangeIsRetried() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(RETRYABLE, LotteryWaitlistChangedException())
        controller.start("owner")
        runCurrent()
        assertTrue(controller.error.value!!.contains("waiting list changed"))
        repository.failures.clear()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, controller.completedDraws.value)
    }

    /** A dismissed notice stays hidden while the same failure repeats, and returns for a new failure. */
    @Test fun dismissedNoticeReturnsOnlyWhenFailuresChange() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(RETRYABLE, Exception("offline"))
        controller.start("owner")
        runCurrent()
        controller.dismiss()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(controller.error.value)
        repository.lotteries.value = listOf(lottery("pine", 99), lottery("oak", 99))
        repository.failures["oak"] = LocalLotteryDrawException(RETRYABLE, Exception("offline"))
        runCurrent()
        val message = controller.error.value!!
        assertTrue(message, message.contains("2 lotteries") && message.contains("Pine Meadow") && message.contains("lottery oak"))
    }

    /** A name lookup failure still identifies the lottery by its ID. */
    @Test fun failedNameLookupFallsBackToTheId() = runForegroundTest {
        repository.lotteries.value = listOf(lottery("pine", 99))
        repository.failures["pine"] = LocalLotteryDrawException(RETRYABLE, Exception("offline"))
        repository.nameFailure = true
        controller.start("owner")
        runCurrent()
        assertTrue(controller.error.value!!.contains("lottery pine"))
    }

    /** A listener rejected by the rules shows the error once and stops resubscribing without crashing. */
    @Test fun permanentListenerFailureStopsResubscribing() = runForegroundTest {
        repository.listenerFailure = LocalLotteryDrawException(PERMISSION_DENIED, Exception("denied"))
        controller.start("owner")
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, repository.subscriptions)
        assertTrue(controller.error.value!!.contains("permission is missing"))
        assertEquals(1, logged.size)
    }

    /** Builds a controller whose log is captured instead of sent to the Android log. */
    private fun controller(now: () -> Timestamp) =
        LocalLotteryDrawController(repository, now) { message, _ -> logged += message }

    /** Stops the foreground timer before runTest drains its scheduler, including after failed assertions. */
    private fun runForegroundTest(test: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { test() } finally { controller.stop() }
    }

    /** Supplies a saved candidate deadline for the foreground eligibility check. */
    private fun lottery(id: String, endSeconds: Long, updated: Long = 1) =
        LocalLotteryDrawCandidate(id, Timestamp(endSeconds, 0), Timestamp(updated, 0))

    /** Tracks subscriptions and successful draws without relying on a backend. */
    private class FakeDraws : LocalLotteryDrawRepository {
        val lotteries = MutableStateFlow<List<LocalLotteryDrawCandidate>>(emptyList())
        val attempts = mutableListOf<String>()
        private val completed = mutableSetOf<String>()
        var failedIds = emptySet<String>()
        val failures = mutableMapOf<String, Exception>()
        var failListener = false
        var listenerFailure: Exception? = null
        var nameFailure = false
        var subscriptions = 0
        var observedOwner: String? = null

        /** Produces the current owner list or the configured listener failure. */
        override fun observeOwnedLotteries(ownerId: String) = flow {
            observedOwner = ownerId
            subscriptions++
            listenerFailure?.let { throw it }
            if (failListener) throw LocalLotteryDrawException(RETRYABLE, Exception("Listener failed"))
            lotteries.collect { emit(it) }
        }

        /** Simulates once-only commits and isolated failures for selected lottery IDs. */
        override suspend fun draw(lotteryId: String): Boolean {
            attempts += lotteryId
            failures[lotteryId]?.let { throw it }
            if (lotteryId in failedIds) throw LocalLotteryDrawException(RETRYABLE, Exception("Draw failed"))
            return completed.add(lotteryId)
        }

        /** Names only the "pine" lottery, so other IDs exercise the fallback. */
        override suspend fun lotteryName(lotteryId: String): String? {
            if (nameFailure) throw LocalLotteryDrawException(RETRYABLE, Exception("offline"))
            return if (lotteryId == "pine") "Pine Meadow" else null
        }
    }
}
