/*
 * AdminCampsitesControllerTest.kt
 *
 * Purpose: Unit tests for the S19 Campsites tab and admin campsite details (US 03.04.01, 03.01.01).
 * Role: Tests AdminCampsitesController and AdminCampsiteController with an in-memory repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.model.Photo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies browsing, searching, and removing campsites as an admin. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCampsitesControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val photos = object : CampsiteDetailsRepository {
        override suspend fun getCampsite(campsiteId: String) = null
        override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?) = emptyList<Photo>()
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** Every campsite is listed, removed ones last, each with its owner's name (US 03.04.01). */
    @Test fun listsEveryCampsiteWithOwnerNames() = runTest(dispatcher) {
        val controller = AdminCampsitesController(FakeAdminRepository())
        runCurrent()
        val rows = controller.state.value.rows
        assertEquals(listOf("Pine Ridge", "Two Jack Lakeside", "Old Meadow"), rows.map { it.listing.campsite.name })
        assertEquals("Jane Doe", rows.first().ownerName)
        assertFalse(rows.last().listing.active)
    }

    /** Search matches the name, location, or owner, ignoring case. */
    @Test fun searchFiltersByNameLocationOrOwner() = runTest(dispatcher) {
        val controller = AdminCampsitesController(FakeAdminRepository())
        runCurrent()
        controller.setQuery("jasper")
        assertEquals(listOf("Pine Ridge"), controller.state.value.shown.map { it.listing.campsite.name })
        controller.setQuery("john")
        assertEquals(listOf("Old Meadow"), controller.state.value.shown.map { it.listing.campsite.name })
        controller.setQuery("nothing")
        assertTrue(controller.state.value.shown.isEmpty())
    }

    /** A campsite whose owner has no profile name still shows a label. */
    @Test fun missingOwnerNameHasFallback() = runTest(dispatcher) {
        val controller = AdminCampsitesController(FakeAdminRepository(profiles = emptyList()))
        runCurrent()
        assertTrue(controller.state.value.rows.all { it.ownerName == "Unnamed owner" })
    }

    /** A failed load shows an error, and Retry recovers. */
    @Test fun failureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val admin = FakeAdminRepository().apply { failure = IllegalStateException("offline") }
        val controller = AdminCampsitesController(admin)
        runCurrent()
        assertEquals("Could not load campsites. Please retry.", controller.state.value.error)
        admin.failure = null
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals(3, controller.state.value.rows.size)
    }

    /** Remove asks first, then marks the listing removed (US 03.01.01). */
    @Test fun removeListingAsksFirstThenRemoves() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        val controller = AdminCampsiteController("lake", admin, photos)
        runCurrent()
        assertEquals("Jane Doe", controller.state.value.ownerName)
        controller.requestRemove()
        assertTrue(controller.state.value.confirmingRemove)
        controller.cancelRemove()
        assertFalse(controller.state.value.confirmingRemove)
        assertTrue(admin.campsites.first { it.campsite.id == "lake" }.active)

        controller.requestRemove()
        controller.confirmRemove()
        runCurrent()
        assertFalse(controller.state.value.listing!!.active)
        assertFalse(admin.campsites.first { it.campsite.id == "lake" }.active)
        assertFalse(controller.state.value.confirmingRemove)
    }

    /** A removed listing cannot be removed again, and a missing one says so. */
    @Test fun removedOrMissingCampsiteCannotBeRemoved() = runTest(dispatcher) {
        val removed = AdminCampsiteController("old", FakeAdminRepository(), photos)
        runCurrent()
        removed.requestRemove()
        assertFalse(removed.state.value.confirmingRemove)

        val missing = AdminCampsiteController("gone", FakeAdminRepository(), photos)
        runCurrent()
        assertEquals("This campsite no longer exists.", missing.state.value.error)
    }

    /** A failed removal keeps the listing active and explains why. */
    @Test fun failedRemovalShowsError() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        val controller = AdminCampsiteController("lake", admin, photos)
        runCurrent()
        admin.failure = IllegalStateException("offline")
        controller.requestRemove()
        controller.confirmRemove()
        runCurrent()
        assertTrue(controller.state.value.listing!!.active)
        assertEquals("Could not remove this campsite. Check your connection and retry.", controller.state.value.actionError)
    }
}
