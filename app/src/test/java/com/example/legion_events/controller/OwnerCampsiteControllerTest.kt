/*
 * OwnerCampsiteControllerTest.kt
 *
 * Purpose: Unit tests for OwnerCampsiteController (US 02.01.04, 02.02.01).
 * Role: Uses FakeOwnerCampsiteRepository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.CampsiteQr
import com.example.legion_events.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCampsiteControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val lake = Campsite("lake", "owner", "Two Jack Lakeside", "Banff", 6, 3800, photoId = "cover")
    private val cover = Photo("cover", "owner", "campsite", "lake", "data", "image/jpeg")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun loadsCampsiteBadgeAndCover() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository(campsites = { listOf(lake) },
            lotteries = { listOf(testLottery("lake", startDays = -10, endDays = -2, drawn = true)) },
            photos = { listOf(cover) })
        val controller = controller(repository)
        controller.refresh()
        runCurrent()
        val state = controller.state.value
        assertEquals(lake, state.campsite)
        assertEquals(LotteryBadge.DRAWN, state.badge)
        assertEquals(cover, state.cover)
        assertNull(state.qrText)
    }

    @Test fun campsiteOwnedBySomeoneElseIsUnavailable() = runTest(dispatcher) {
        val controller = controller(FakeOwnerCampsiteRepository())
        controller.refresh()
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.campsite)
        assertNull(controller.state.value.error)
        controller.generateQr()
        assertNull(controller.state.value.qrText)
    }

    @Test fun failureCanBeRetried() = runTest(dispatcher) {
        var fail = true
        val controller = controller(FakeOwnerCampsiteRepository(campsites = { if (fail) error("Offline") else listOf(lake) }))
        controller.refresh()
        runCurrent()
        assertNotNull(controller.state.value.error)
        fail = false
        controller.refresh()
        runCurrent()
        assertEquals(lake, controller.state.value.campsite)
    }

    @Test fun generatedQrHoldsOnlyTheCampsiteIdAndSurvivesRefresh() = runTest(dispatcher) {
        val controller = controller(FakeOwnerCampsiteRepository(campsites = { listOf(lake) }))
        controller.refresh()
        runCurrent()
        controller.generateQr()
        assertEquals("legionevents://campsite/lake", controller.state.value.qrText)
        assertEquals("lake", CampsiteQr.decode(controller.state.value.qrText!!))
        controller.refresh()
        runCurrent()
        assertNotNull(controller.state.value.qrText)
    }

    @Test fun qrDecodingRejectsOtherCodes() {
        assertNull(CampsiteQr.decode("https://example.com"))
        assertNull(CampsiteQr.decode("legionevents://campsite/"))
        assertNull(CampsiteQr.decode("legionevents://campsite/a/b"))
        assertEquals("abc", CampsiteQr.decode(" legionevents://campsite/abc\n"))
    }

    /** Creates an entry-scoped controller with a fixed clock. */
    private fun controller(repository: FakeOwnerCampsiteRepository): OwnerCampsiteController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { OwnerCampsiteController("lake", repository) { NOW } }
        })[OwnerCampsiteController::class]
}
