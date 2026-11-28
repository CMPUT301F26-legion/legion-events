package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.LocalBookingRepository
import com.example.legion_events.data.LocalBookingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalBookingControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val calls = mutableListOf<String>()
    private var result = LocalBookingResult(0)
    private val controller by lazy { ViewModelProvider.create(store, viewModelFactory {
        initializer { LocalBookingController(object : LocalBookingRepository {
            override suspend fun process(ownerId: String): LocalBookingResult { calls += ownerId; return result }
        }, log = {}) }
    })[LocalBookingController::class] }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun checksImmediatelyEveryFiveSecondsAndStopsInBackground() = foregroundTest {
        controller.start("owner"); runCurrent()
        controller.start("owner"); runCurrent()
        assertEquals(listOf("owner"), calls)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, calls.size)
        controller.stop(); advanceTimeBy(20000); runCurrent()
        assertEquals(2, calls.size)
        controller.start("owner"); runCurrent()
        assertEquals(3, calls.size)
    }
    @Test fun changingAccountStopsOldSessionAndStartsNewScope() = foregroundTest {
        controller.start("owner"); runCurrent()
        controller.start("other"); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(listOf("owner", "other", "other"), calls)
    }
    @Test fun partialProgressRefreshesOwnerViewsAndFailureCanBeDismissedOrRetried() = foregroundTest {
        result = LocalBookingResult(1, listOf(IllegalStateException("Malformed independent record")))
        controller.start("owner"); runCurrent()
        assertEquals(1, controller.revision.value)
        assertNotNull(controller.error.value)
        controller.dismiss(); assertNull(controller.error.value)
        advanceTimeBy(5000); runCurrent()
        assertNull(controller.error.value)
        controller.retry(); runCurrent()
        assertNotNull(controller.error.value)
        result = LocalBookingResult(0)
        advanceTimeBy(5000); runCurrent()
        assertNull(controller.error.value)
    }
    private fun foregroundTest(test: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { test() } finally { controller.stop() }
    }
}
