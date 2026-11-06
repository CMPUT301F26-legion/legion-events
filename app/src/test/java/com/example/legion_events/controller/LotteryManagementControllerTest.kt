/*
 * LotteryManagementControllerTest.kt
 *
 * Purpose: Unit tests for the owner's Lotteries tab and S16 controllers (US 02.04.06, 02.09.01).
 * Role: Uses fake repositories.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Tests the owner's Lotteries tab and S16 controllers, and their shared helpers. */
@OptIn(ExperimentalCoroutinesApi::class)
class LotteryManagementControllerTest {
    private val dispatcher = StandardTestDispatcher()
    /** "Today" for every test: 2026-11-04 at midnight UTC. */
    private val today = day("2026-11-04")
    private val campsites = listOf(
        Campsite("c1", "owner", "Lakeside", "Jasper", 4, 6550),
        Campsite("c2", "owner", "Ridge", "Banff", 2, 4000),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Midnight UTC at the start of the given day. */
    private fun day(value: String): Timestamp =
        Timestamp(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!)

    /** A lottery whose registration runs between two days. */
    private fun lottery(id: String, opens: String, closes: String, campsiteIds: List<String> = listOf("c1")) = Lottery(
        id = id, ownerId = "owner", campsiteIds = campsiteIds,
        registrationStartAt = day(opens), registrationEndAt = day(closes),
        bookingStartAt = day("2027-06-01"), bookingEndAt = day("2027-07-01"),
        winnerCount = 3, waitlistLimit = 50,
    )

    private fun entry(user: String, status: String, joined: String?) =
        WaitlistEntrant(user, status, joined?.let(::day))

    private fun TestScope.manage(repository: FakeRepository, campsites: suspend () -> List<Campsite> = { this@LotteryManagementControllerTest.campsites }) =
        LotteryManagementController("L1", repository, campsites, { today.toDate() }).also { runCurrent() }

    private fun TestScope.list(repository: FakeRepository) =
        OwnerLotteriesController(repository, { campsites }, { today.toDate() }).also { runCurrent() }

    @Test
    fun titleUsesCampsiteNames() {
        val names = campsites.associate { it.id to it.name }
        assertEquals("Lakeside", lotteryTitle(lottery("a", "2026-12-01", "2026-12-11"), names))
        assertEquals("Lakeside + 1 more", lotteryTitle(lottery("a", "2026-12-01", "2026-12-11", listOf("c1", "c2")), names))
        assertEquals("Lottery", lotteryTitle(lottery("a", "2026-12-01", "2026-12-11"), emptyMap()))
    }

    @Test
    fun waitlistShowsOnlyJoinedEntrantsInJoiningOrder() {
        val rows = waitlistRows(listOf(
            entry("late", "joined", "2026-11-03"),
            entry("gone", "withdrawn", "2026-11-01"),
            entry("early", "joined", "2026-11-02"),
            entry("unknown", "joined", null),
        ), "joined")
        assertEquals(listOf(1, 2, 3), rows.map { it.position })
        assertEquals(listOf(day("2026-11-02"), day("2026-11-03"), null), rows.map { it.joinedAt })
        assertEquals("Entrant 1", rows.first().label)
    }

    @Test
    fun entrantKeepsTheSameLabelInBothLists() {
        val entries = listOf(
            entry("first", "lost", "2026-11-01"),
            entry("second", "won", "2026-11-02"),
            entry("gone", "withdrawn", "2026-11-02"),
            entry("third", "lost", "2026-11-03"),
        )
        assertEquals(listOf("Entrant 2"), waitlistRows(entries, "won").map { it.label })
        assertEquals(listOf("Entrant 1", "Entrant 3"), waitlistRows(entries, "lost").map { it.label })
        assertEquals(listOf(1, 2), waitlistRows(entries, "lost").map { it.position })
    }

    @Test
    fun afterDrawWaitlistShowsNotPickedAndWinnersShowsPicked() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            lotteries = listOf(lottery("L1", "2026-10-01", "2026-10-10").copy(winnersDrawn = true))
            waitlist = listOf(entry("a", "won", "2026-10-02"), entry("b", "lost", "2026-10-03"), entry("c", "won", "2026-10-04"))
        }
        with(manage(repository).state.value) {
            assertEquals(LotteryBadge.DRAWN, badge)
            assertEquals(listOf("Entrant 1", "Entrant 3"), winners.map { it.label })
            assertEquals(listOf("Entrant 2"), waitlist.map { it.label })
        }
    }

    @Test
    fun beforeDrawThereAreNoWinners() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11"))
            waitlist = listOf(entry("a", "joined", "2026-11-02"))
        }
        with(manage(repository).state.value) {
            assertTrue(winners.isEmpty())
            assertEquals(1, waitlist.size)
        }
    }

    @Test
    fun managementLoadsLotteryAndWaitlist() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11"))
            waitlist = listOf(entry("a", "joined", "2026-11-02"), entry("b", "joined", "2026-11-01"))
        }
        with(manage(repository).state.value) {
            assertFalse(loading)
            assertEquals("Lakeside", title)
            assertEquals(LotteryBadge.UPCOMING, badge)
            assertNull(editBlockedReason)
            assertEquals(2, waitlist.size)
            assertEquals(day("2026-11-01"), waitlist.first().joinedAt)
        }
        assertEquals("L1", repository.waitlistRequestedFor)
    }

    @Test
    fun openLotteryCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeRepository().apply { lotteries = listOf(lottery("L1", "2026-11-01", "2026-11-20")) }
        with(manage(repository).state.value) {
            assertEquals(LotteryBadge.OPEN, badge)
            assertNotNull(editBlockedReason)
        }
    }

    @Test
    fun waitlistFailureStillShowsLottery() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11"))
            failWaitlist = true
        }
        with(manage(repository).state.value) {
            assertNull(error)
            assertNotNull(lottery)
            assertNotNull(waitlistError)
        }
    }

    @Test
    fun campsiteFailureFallsBackToGenericTitle() = runTest(dispatcher) {
        val repository = FakeRepository().apply { lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11")) }
        assertEquals("Lottery", manage(repository) { error("offline") }.state.value.title)
    }

    @Test
    fun lotteryFailureShowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failLottery = true }
        val controller = manage(repository)
        assertNotNull(controller.state.value.error)
        repository.failLottery = false
        repository.lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11"))
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals("Lakeside", controller.state.value.title)
    }

    @Test
    fun selectedTabSurvivesRefresh() = runTest(dispatcher) {
        val repository = FakeRepository().apply { lotteries = listOf(lottery("L1", "2026-12-01", "2026-12-11")) }
        val controller = manage(repository)
        controller.selectTab(ManagementTab.WINNERS)
        controller.refresh()
        runCurrent()
        assertEquals(ManagementTab.WINNERS, controller.state.value.tab)
    }

    @Test
    fun listOrdersOpenBeforeUpcomingBeforeDrawn() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            lotteries = listOf(
                lottery("drawn", "2026-10-01", "2026-10-10").copy(winnersDrawn = true),
                lottery("upcoming", "2026-12-01", "2026-12-11"),
                lottery("open", "2026-11-01", "2026-11-20"),
            )
        }
        with(list(repository).state.value) {
            assertFalse(loading)
            assertEquals(listOf("open", "upcoming", "drawn"), items.map { it.lottery.id })
            assertEquals(LotteryBadge.DRAWN, items.last().badge)
        }
    }

    @Test
    fun listFailureShowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failLottery = true }
        val controller = list(repository)
        assertNotNull(controller.state.value.error)
        repository.failLottery = false
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.items.isEmpty())
    }

    /** In-memory repository for the read paths these screens use. */
    private class FakeRepository : LotteryRepository {
        var lotteries: List<Lottery> = emptyList()
        var waitlist: List<WaitlistEntrant> = emptyList()
        var failLottery = false
        var failWaitlist = false
        var waitlistRequestedFor: String? = null

        override suspend fun createLottery(lottery: Lottery) = error("unused")
        override suspend fun updateLottery(lottery: Lottery) = error("unused")

        override suspend fun getLottery(id: String): Lottery {
            if (failLottery) error("offline")
            return lotteries.first { it.id == id }
        }

        override suspend fun getMyLotteries(): List<Lottery> {
            if (failLottery) error("offline")
            return lotteries
        }

        override suspend fun getWaitlist(lotteryId: String): List<WaitlistEntrant> {
            waitlistRequestedFor = lotteryId
            if (failWaitlist) error("offline")
            return waitlist
        }
    }
}
