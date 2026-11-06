/*
 * WaitlistControllerTest.kt
 *
 * Purpose: Unit tests for joining from S04 and leaving from S06 (US 01.01.06, 01.02.01, 01.02.02).
 * Role: Tests CampsiteLotteryController and MyLotteriesController with an in-memory repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies the join and leave rules the entrant sees on S04 and S06. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitlistControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    /** Fixed "now": day 100, in seconds. */
    private val now = Timestamp(100L * DAY, 0)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    /** S04 shows only lotteries whose registration is open now (US 01.01.06). */
    @Test fun detailsShowOnlyOpenLotteries() = runTest(dispatcher) {
        val repository = repository(
            lottery("open", startDay = 90, endDay = 110),
            lottery("future", startDay = 105, endDay = 120),
            lottery("closed", startDay = 80, endDay = 95),
            lottery("drawn", startDay = 90, endDay = 110).copy(winnersDrawn = true),
        )
        val controller = details(repository)
        runCurrent()
        assertEquals(listOf("open"), controller.state.value.lotteries.map { it.lottery.id })
    }

    /** Joining saves one entry, raises the count, and a second join is refused (US 01.02.01). */
    @Test fun joinSavesOnceAndShowsConfirmation() = runTest(dispatcher) {
        val repository = repository(lottery("open", startDay = 90, endDay = 110))
        val controller = details(repository)
        runCurrent()
        controller.join("open")
        runCurrent()
        assertEquals("open", controller.state.value.joinedLotteryId)
        assertEquals(WaitlistEntrant.STATUS_JOINED, controller.state.value.lotteries.single().status)
        assertEquals(1, repository.lotteries.getValue("open").entrantCount)
        controller.join("open")
        runCurrent()
        assertEquals("You are already on this waitlist.", controller.state.value.actionError)
        assertEquals(1, repository.joins)
    }

    /** A full waitlist cannot be joined, and the reason is shown. */
    @Test fun fullWaitlistIsRefused() = runTest(dispatcher) {
        val repository = repository(lottery("full", startDay = 90, endDay = 110).copy(waitlistLimit = 2, entrantCount = 2))
        val controller = details(repository)
        runCurrent()
        controller.join("full")
        runCurrent()
        assertEquals("This waitlist is full.", controller.state.value.actionError)
        assertEquals(0, repository.joins)
    }

    /** Leaving asks first, then marks the entry withdrawn and lowers the count (US 01.02.02). */
    @Test fun leaveNeedsConfirmationThenWithdraws() = runTest(dispatcher) {
        val repository = repository(lottery("open", startDay = 90, endDay = 110).copy(entrantCount = 1))
        repository.statuses["open"] = WaitlistEntrant.STATUS_JOINED
        val controller = mine(repository)
        runCurrent()
        val entry = controller.state.value.entries.single()
        assertTrue(controller.canLeave(entry))
        controller.requestLeave("open")
        controller.cancelLeave()
        runCurrent()
        assertNull(controller.state.value.confirmingLeave)
        assertEquals(WaitlistEntrant.STATUS_JOINED, repository.statuses["open"])
        controller.requestLeave("open")
        controller.confirmLeave()
        runCurrent()
        assertEquals(WaitlistEntrant.STATUS_WITHDRAWN, repository.statuses["open"])
        assertEquals(0, repository.lotteries.getValue("open").entrantCount)
        assertFalse(controller.canLeave(controller.state.value.entries.single()))
    }

    /** After registration closes, Leave is not offered. */
    @Test fun cannotLeaveAfterRegistrationCloses() = runTest(dispatcher) {
        val repository = repository(lottery("closed", startDay = 80, endDay = 95))
        repository.statuses["closed"] = WaitlistEntrant.STATUS_JOINED
        val controller = mine(repository)
        runCurrent()
        assertFalse(controller.canLeave(controller.state.value.entries.single()))
    }

    /** A failed load shows an error and Retry recovers. */
    @Test fun failedLoadCanRetry() = runTest(dispatcher) {
        val repository = repository(lottery("open", startDay = 90, endDay = 110))
        repository.offline = true
        val controller = mine(repository)
        runCurrent()
        assertNotNull(controller.state.value.error)
        repository.offline = false
        controller.retry()
        runCurrent()
        assertNull(controller.state.value.error)
    }

    private fun repository(vararg lotteries: Lottery) =
        FakeWaitlistRepository(lotteries.associateBy { it.id }.toMutableMap()) { now }

    private fun details(repository: FakeWaitlistRepository): CampsiteLotteryController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { CampsiteLotteryController("lake", repository) { now } }
        })[CampsiteLotteryController::class]

    private fun mine(repository: FakeWaitlistRepository): MyLotteriesController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { MyLotteriesController(repository) { now } }
        })[MyLotteriesController::class]

    private companion object {
        const val DAY = 86_400L

        /** A lottery for campsite "lake" whose registration runs from [startDay] to [endDay]. */
        fun lottery(id: String, startDay: Long, endDay: Long) = Lottery(
            id = id, ownerId = "owner", campsiteIds = listOf("lake"),
            registrationStartAt = Timestamp(startDay * DAY, 0), registrationEndAt = Timestamp(endDay * DAY, 0),
            bookingStartAt = Timestamp((endDay + 1) * DAY, 0), bookingEndAt = Timestamp((endDay + 10) * DAY, 0),
            winnerCount = 1, waitlistLimit = 10,
        )
    }
}
