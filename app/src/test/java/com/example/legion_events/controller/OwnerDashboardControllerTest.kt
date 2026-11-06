/*
 * OwnerDashboardControllerTest.kt
 *
 * Purpose: Unit tests for OwnerDashboardController and lottery badges (US 02.03.01).
 * Role: Uses FakeOwnerCampsiteRepository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerDashboardControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val lake = Campsite("lake", "owner", "Two Jack Lakeside", "Banff", 6, 3800, photoId = "cover")
    private val creek = Campsite("creek", "owner", "Whistlers", "Jasper", 8, 4500)
    private val cover = Photo("cover", "owner", "campsite", "lake", "data", "image/jpeg")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun badgesPreferTheMostActiveLottery() {
        val lotteries = listOf(
            testLottery("lake", startDays = -30, endDays = -20, drawn = true),
            testLottery("lake", startDays = -1, endDays = 5),
            testLottery("creek", startDays = 3, endDays = 9),
        )
        assertEquals(LotteryBadge.OPEN, lotteryBadge("lake", lotteries, NOW))
        assertEquals(LotteryBadge.UPCOMING, lotteryBadge("creek", lotteries, NOW))
        assertEquals(LotteryBadge.NONE, lotteryBadge("other", lotteries, NOW))
        assertEquals(LotteryBadge.CLOSED, lotteryBadge(testLottery("x", startDays = -5, endDays = -1), NOW))
        assertEquals(LotteryBadge.DRAWN, lotteryBadge(testLottery("x", startDays = -5, endDays = -1, drawn = true), NOW))
    }

    @Test fun refreshListsCampsitesWithBadgesOpenCountAndCovers() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository(
            campsites = { listOf(lake, creek) },
            lotteries = { listOf(testLottery("lake", startDays = -1, endDays = 5)) },
            photos = { ids -> listOf(cover).filter { it.id in ids } },
        )
        val controller = controller(repository)
        assertTrue(controller.state.value.loading)
        controller.refresh()
        runCurrent()
        val state = controller.state.value
        assertFalse(state.loading)
        assertEquals(listOf(OwnerCampsiteItem(lake, LotteryBadge.OPEN), OwnerCampsiteItem(creek, LotteryBadge.NONE)), state.campsites)
        assertEquals(1, state.openLotteries)
        assertFalse(state.lotteriesUnavailable)
        assertEquals(mapOf("cover" to cover), state.covers)
    }

    @Test fun lotteryFailureStillShowsCampsitesWithoutBadges() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository(campsites = { listOf(creek) }, lotteries = { error("Denied") })
        val controller = controller(repository)
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.lotteriesUnavailable)
        assertEquals(listOf(OwnerCampsiteItem(creek, null)), controller.state.value.campsites)
    }

    @Test fun campsiteFailureShowsErrorAndRefreshRecovers() = runTest(dispatcher) {
        var fail = true
        val repository = FakeOwnerCampsiteRepository(campsites = { if (fail) error("Offline") else listOf(creek) })
        val controller = controller(repository)
        controller.refresh()
        runCurrent()
        assertNotNull(controller.state.value.error)
        fail = false
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals(1, controller.state.value.campsites.size)
    }

    @Test fun refreshKeepsCurrentListVisibleAndDoesNotRefetchCachedCovers() = runTest(dispatcher) {
        var photoRequests = 0
        val repository = FakeOwnerCampsiteRepository(campsites = { listOf(lake) },
            photos = { photoRequests++; listOf(cover) })
        val controller = controller(repository)
        controller.refresh()
        runCurrent()
        controller.refresh()
        assertFalse(controller.state.value.loading)
        assertEquals(1, controller.state.value.campsites.size)
        runCurrent()
        assertEquals(2, repository.listCalls)
        assertEquals(1, photoRequests)
    }

    /** Creates an entry-scoped controller with a fixed clock. */
    private fun controller(repository: FakeOwnerCampsiteRepository): OwnerDashboardController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { OwnerDashboardController(repository) { NOW } }
        })[OwnerDashboardController::class]
}
