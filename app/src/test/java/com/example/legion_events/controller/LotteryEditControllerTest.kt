package com.example.legion_events.controller

import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
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

    /** Creates a controller and lets it finish loading. */
    private fun TestScope.controller(
        repository: FakeRepository = FakeRepository(),
        lotteryId: String? = null,
    ): LotteryEditController {
        val controller = LotteryEditController(lotteryId, repository, { campsites }, utc)
        runCurrent()
        return controller
    }

    /** Fills every field with valid values. */
    private fun LotteryEditController.fillValid() {
        setTitle("Summer draw")
        toggleCampsite("c1")
        toggleCampsite("c2")
        setRegistrationStart("2026-12-01")
        setRegistrationEnd("2026-12-10")
        setBookingStart("2027-06-01")
        setBookingEnd("2027-06-30")
        setNumWinners("3")
        setWaitlistCapacity("20")
    }

    /** Midnight UTC on the given day. */
    private fun day(value: String) =
        Timestamp(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.parse(value)!!)

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
        assertEquals("Summer draw", created.title)
        assertEquals(listOf("c1", "c2"), created.campsiteIds)
        assertEquals(day("2026-12-01"), created.registrationStart)
        assertEquals(day("2026-12-11"), created.registrationEnd) // closes when Dec 10 ends
        assertEquals("2027-06-01", created.bookingStart)
        assertEquals(3, created.numWinners)
        assertEquals(20, created.waitlistCapacity)
        assertEquals("new-id", controller.state.value.savedLotteryId)
    }

    @Test
    fun emptyCapacityMeansNoLimit() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.fillValid()
        controller.setWaitlistCapacity("")
        controller.save()
        runCurrent()
        assertNull(repository.created!!.waitlistCapacity)
    }

    @Test
    fun emptyFormShowsErrorsAndSavesNothing() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.save()
        runCurrent()
        assertEquals(
            setOf(LotteryField.TITLE, LotteryField.CAMPSITES, LotteryField.REGISTRATION, LotteryField.BOOKING, LotteryField.WINNERS),
            controller.state.value.errors.keys,
        )
        assertNull(repository.created)
    }

    @Test
    fun bookingMustStartAfterRegistrationEnds() = runTest(dispatcher) {
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
    fun winnersMustBeAtLeastOne() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setNumWinners("0")
        controller.save()
        assertTrue(LotteryField.WINNERS in controller.state.value.errors)
    }

    @Test
    fun capacityCannotBeBelowWinners() = runTest(dispatcher) {
        val controller = controller()
        controller.fillValid()
        controller.setWaitlistCapacity("2")
        controller.save()
        assertTrue(LotteryField.CAPACITY in controller.state.value.errors)
    }

    @Test
    fun editingFieldClearsItsError() = runTest(dispatcher) {
        val controller = controller()
        controller.save()
        controller.setTitle("Fixed")
        assertFalse(LotteryField.TITLE in controller.state.value.errors)
        assertTrue(LotteryField.CAMPSITES in controller.state.value.errors)
    }

    @Test
    fun editLoadsSavedValuesAndUpdates() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved() }
        val controller = controller(repository, lotteryId = "L1")
        with(controller.state.value) {
            assertTrue(editing)
            assertEquals("2026-12-01", registrationStart)
            assertEquals("2026-12-10", registrationEnd)
            assertEquals(setOf("c1"), selectedCampsiteIds)
            assertEquals("", waitlistCapacity)
        }
        controller.setNumWinners("5")
        controller.save()
        runCurrent()
        assertNull(repository.created)
        assertEquals("L1", repository.updated!!.id)
        assertEquals(5, repository.updated!!.numWinners)
        assertEquals(day("2026-12-11"), repository.updated!!.registrationEnd)
    }

    @Test
    fun drawnLotteryCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeRepository().apply { existing = saved().copy(drawn = true) }
        val controller = controller(repository, lotteryId = "L1")
        assertTrue(controller.state.value.locked)
        controller.setTitle("Changed")
        controller.save()
        runCurrent()
        assertEquals("Summer draw", controller.state.value.title)
        assertNull(repository.updated)
    }

    @Test
    fun saveFailureShowsMessageAndAllowsRetry() = runTest(dispatcher) {
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
        assertTrue(controller.state.value.loadError != null)
        repository.failLoad = false
        repository.existing = saved()
        controller.retry()
        runCurrent()
        assertNull(controller.state.value.loadError)
        assertEquals("Summer draw", controller.state.value.title)
    }

    /** A saved lottery as Firestore would return it. */
    private fun saved() = Lottery(
        id = "L1", ownerId = "owner", title = "Summer draw", campsiteIds = listOf("c1"),
        registrationStart = day("2026-12-01"), registrationEnd = day("2026-12-11"),
        bookingStart = "2027-06-01", bookingEnd = "2027-06-30", numWinners = 3,
    )

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

        override suspend fun getLotteriesForOwner(ownerId: String) = listOfNotNull(existing)
    }
}