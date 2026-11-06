/*
 * LotteryEditControllerTest.kt
 *
 * Purpose: Unit tests for LotteryEditController validation and saving (US 02.04.01 to 02.04.07).
 * Role: Uses a fake LotteryRepository.
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

@OptIn(ExperimentalCoroutinesApi::class)
class LotteryEditControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val utc = TimeZone.getTimeZone("UTC")
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
        Timestamp(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.parse(value)!!)

    /** Creates a controller with a fixed clock and lets it finish loading. */
    private fun TestScope.controller(
        repository: FakeRepository = FakeRepository(),
        lotteryId: String? = null,
    ): LotteryEditController {
        val controller = LotteryEditController(lotteryId, repository, { campsites }, utc, { today.toDate() })
        runCurrent()
        return controller
    }

    /** Fills every field with valid values. */
    private fun LotteryEditController.fillValid() {
        toggleCampsite("c1")
        toggleCampsite("c2")
        setRegistrationStart("2026-12-01")
        setRegistrationEnd("2026-12-10")
        setBookingStart("2027-06-01")
        setBookingEnd("2027-06-30")
        setWinnerCount("3")
        setWaitlistLimit("20")
    }

    /** A saved lottery that can still be edited. */
    private fun saved() = Lottery(
        id = "L1", ownerId = "owner", campsiteIds = listOf("c1"),
        registrationStartAt = day("2026-12-01"), registrationEndAt = day("2026-12-11"),
        bookingStartAt = day("2027-06-01"), bookingEndAt = day("2027-07-01"),
        winnerCount = 3, waitlistLimit = 100,
    )

    @Test
    fun loadsOwnerCampsitesForNewLottery() = runTest(dispatcher) {
        val state = controller().state.value
        assertFalse(state.loading)
        assertFalse(state.editing)
        assertEquals(listOf("c1", "c2"), state.campsites.map { it.id })
    }

    @Test
    fun createsLotteryWithMultipleCampsites() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.fillValid()
        controller.save()
        runCurrent()
        val created = repository.created!!
        assertEquals(listOf("c1", "c2"), created.campsiteIds)
        assertEquals(day("2026-12-01"), created.registrationStartAt)
        assertEquals(day("2026-12-11"), created.registrationEndAt) // closes when Dec 10 ends
        assertEquals(day("2027-06-01"), created.bookingStartAt)
        assertEquals(day("2027-07-01"), created.bookingEndAt) // closes when Jun 30 ends
        assertEquals(3, created.winnerCount)
        assertEquals(20, created.waitlistLimit)
        assertEquals("new-id", controller.state.value.savedLotteryId)
    }

    @Test
    fun emptyLimitSavesMaximum() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.fillValid()
        controller.setWaitlistLimit("")
        controller.save()
        runCurrent()
        assertEquals(100, repository.created!!.waitlistLimit)
    }

    @Test
    fun emptyFormShowsErrorsAndSavesNothing() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.save()
        runCurrent()
        assertEquals(
            setOf(LotteryField.CAMPSITES, LotteryField.REGISTRATION, LotteryField.BOOKING, LotteryField.WINNERS),
            controller.state.value.errors.keys,
        )
        assertNull(repository.created)
    }

    @Test
    fun bookingMustOpenAfterRegistrationEnds() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setBookingStart("2026-12-10")
        controller.save()
        assertTrue(LotteryField.BOOKING in controller.state.value.errors)
    }

    @Test
    fun registrationCannotEndBeforeItStarts() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setRegistrationEnd("2026-11-30")
        controller.save()
        assertTrue(LotteryField.REGISTRATION in controller.state.value.errors)
    }

    @Test
    fun registrationCannotEndInThePast() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setRegistrationStart("2026-10-01")
        controller.setRegistrationEnd("2026-11-03")
        controller.save()
        assertTrue(LotteryField.REGISTRATION in controller.state.value.errors)
    }

    @Test
    fun registrationEndingTodayIsAllowed() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.fillValid()
        controller.setRegistrationStart("2026-11-01")
        controller.setRegistrationEnd("2026-11-04")
        controller.save()
        runCurrent()
        assertNotNull(repository.created)
    }

    @Test
    fun winnersMustBeAtLeastOne() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setWinnerCount("0")
        controller.save()
        assertTrue(LotteryField.WINNERS in controller.state.value.errors)
    }

    @Test
    fun winnersCannotExceedLimit() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setWaitlistLimit("2")
        controller.save()
        assertTrue(LotteryField.WINNERS in controller.state.value.errors)
    }

    @Test
    fun limitCannotExceedMaximum() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setWaitlistLimit("101")
        controller.save()
        assertTrue(LotteryField.LIMIT in controller.state.value.errors)
    }

    @Test
    fun editingFieldClearsItsError() = runTest(dispatcher) {
        val controller = controller()
        controller.save()
        controller.toggleCampsite("c1")
        assertFalse(LotteryField.CAMPSITES in controller.state.value.errors)
        assertTrue(LotteryField.WINNERS in controller.state.value.errors)
    }

    @Test
    fun editLoadsSavedValuesAndUpdates() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved() }
        val controller = controller(repository, lotteryId = "L1")
        with(controller.state.value) {
            assertTrue(editing)
            assertFalse(locked)
            assertEquals("2026-12-01", registrationStart)
            assertEquals("2026-12-10", registrationEnd)
            assertEquals("2027-06-30", bookingEnd)
            assertEquals(setOf("c1"), selectedCampsiteIds)
            assertEquals("100", waitlistLimit)
        }
        controller.setWinnerCount("5")
        controller.save()
        runCurrent()
        assertNull(repository.created)
        assertEquals("L1", repository.updated!!.id)
        assertEquals(5, repository.updated!!.winnerCount)
        assertEquals(day("2026-12-11"), repository.updated!!.registrationEndAt)
    }

    @Test
    fun drawnLotteryCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved().copy(winnersDrawn = true) }
        val controller = controller(repository, lotteryId = "L1")
        assertTrue(controller.state.value.locked)
        controller.setWinnerCount("9")
        controller.save()
        runCurrent()
        assertEquals("3", controller.state.value.winnerCount)
        assertNull(repository.updated)
    }

    @Test
    fun lotteryWithEntrantsCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved().copy(entrantCount = 1) }
        assertTrue(controller(repository, lotteryId = "L1").state.value.locked)
    }

    @Test
    fun lotteryWithOpenRegistrationCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved().copy(registrationStartAt = day("2026-11-01")) }
        assertTrue(controller(repository, lotteryId = "L1").state.value.locked)
    }

    @Test
    fun saveFailureShowsMessage() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failWrites = true }
        val controller = controller(repository)
        controller.fillValid()
        controller.save()
        runCurrent()
        assertFalse(controller.state.value.saving)
        assertNull(controller.state.value.savedLotteryId)
        assertTrue(controller.state.value.message!!.startsWith("Could not save"))
    }

    @Test
    fun loadFailureShowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failLoad = true }
        val controller = controller(repository, lotteryId = "L1")
        assertNotNull(controller.state.value.loadError)
        repository.failLoad = false
        repository.existing = saved()
        controller.retry()
        runCurrent()
        assertNull(controller.state.value.loadError)
        assertEquals("3", controller.state.value.winnerCount)
    }

    /** In-memory repository that records what the controller saves. */
    private class FakeRepository : LotteryRepository {
        var existing: Lottery? = null
        var created: Lottery? = null
        var updated: Lottery? = null
        var failLoad = false
        var failWrites = false

        override suspend fun createLottery(lottery: Lottery): String {
            if (failWrites) error("offline")
            created = lottery
            return "new-id"
        }

        override suspend fun updateLottery(lottery: Lottery) {
            if (failWrites) error("offline")
            updated = lottery
        }

        override suspend fun getLottery(id: String): Lottery {
            if (failLoad) error("offline")
            return checkNotNull(existing)
        }

        override suspend fun getMyLotteries() = listOfNotNull(existing)

        override suspend fun getWaitlist(lotteryId: String) = emptyList<WaitlistEntrant>()
    }
}
