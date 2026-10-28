package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.model.Campsite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampsiteListControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val sites = listOf(
        Campsite("pine", "alice", "Forêt Camp", "Banff", 6, 10_001, amenities = listOf("Water")),
        Campsite("spruce", "bob", "Spruce Creek", "Jasper", 2, 6500),
        Campsite("meadow", "alice", "Meadow", "Banff", 4, 10_000, tags = listOf("Quiet")),
    )

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun searchMatchesAllWordsWithoutCaseOrAccentDifferences() {
        assertEquals(listOf("pine"), CampsiteListState(sites, "FORET banff water").visible.map { it.id })
        assertEquals(listOf("meadow"), CampsiteListState(sites, "quiet").visible.map { it.id })
        assertTrue(CampsiteListState(sites, "forest nonexistent").visible.isEmpty())
        assertEquals(sites, CampsiteListState(sites, "  ").visible)
    }

    @Test fun guestAndBudgetFiltersUseStoredCapacityAndIntegerCents() {
        assertEquals(listOf("pine", "meadow"), CampsiteListState(sites, filter = CampsiteFilter.GROUPS).visible.map { it.id })
        assertEquals(listOf("spruce", "meadow"), CampsiteListState(sites, filter = CampsiteFilter.BUDGET).visible.map { it.id })
        assertEquals(listOf("meadow"), CampsiteListState(sites, "banff", CampsiteFilter.BUDGET).visible.map { it.id })
    }

    @Test fun searchAndFilterAreRestoredFromSavedState() = runTest(dispatcher) {
        val repository = object : CampsiteRepository { override fun observeCampsites() = flowOf(sites) }
        val saved = SavedStateHandle(mapOf("query" to "banff", "filter" to "BUDGET"))
        val controller = controller(repository, saved)
        runCurrent()
        assertEquals(listOf("meadow"), controller.state.value.visible.map { it.id })
        controller.search("jasper")
        controller.filter(CampsiteFilter.ALL)
        assertEquals("jasper", saved.get<String>("query"))
        assertEquals("ALL", saved.get<String>("filter"))
        assertFalse(controller.state.value.loading)
    }

    @Test fun retryRecoversFromFailureAndKeepsOnlyOneListener() = runTest(dispatcher) {
        var fail = true
        var active = 0
        val repository = object : CampsiteRepository {
            override fun observeCampsites() = flow {
                active++
                try {
                    if (fail) error("Connection unavailable. Retry.")
                    emit(sites)
                    awaitCancellation()
                } finally { active-- }
            }
        }
        val controller = controller(repository, SavedStateHandle())
        runCurrent()
        assertEquals("Connection unavailable. Retry.", controller.state.value.error)
        assertFalse(controller.state.value.loading)
        fail = false
        repeat(5) { controller.retry() }
        runCurrent()
        assertEquals(1, active)
        assertEquals(sites, controller.state.value.visible)
        assertNull(controller.state.value.error)
        store.clear()
        runCurrent()
        assertEquals(0, active)
    }

    private fun controller(repository: CampsiteRepository, saved: SavedStateHandle): CampsiteListController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { CampsiteListController(repository, saved) }
        })[CampsiteListController::class]
}
