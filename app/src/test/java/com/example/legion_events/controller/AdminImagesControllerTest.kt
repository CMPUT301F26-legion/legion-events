/*
 * AdminImagesControllerTest.kt
 *
 * Purpose: Unit tests for the S19 Images tab (US 03.06.01, 03.03.01).
 * Role: Tests AdminImagesController with in-memory image and admin repositories.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.ModeratedCampsite
import com.example.legion_events.model.Campsite
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

/** Verifies browsing and removing uploaded images as an admin. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminImagesControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** Every image is listed in repository order with its campsite and owner (US 03.06.01). */
    @Test fun listsEveryImageWithCampsiteAndOwner() = runTest(dispatcher) {
        val controller = AdminImagesController(FakeAdminImageRepository(), FakeAdminRepository())
        runCurrent()
        val rows = controller.state.value.rows
        assertEquals(listOf("p3", "p2", "p1"), rows.map { it.photo.id })
        assertEquals(listOf("Two Jack Lakeside", "Pine Ridge", "Unknown campsite"), rows.map { it.placeName })
        assertEquals(listOf("Jane Doe", "Jane Doe", "Unnamed owner"), rows.map { it.ownerName })
        assertFalse(controller.state.value.loading)
    }

    /** An image is marked as a cover only when its campsite's photoId points at it. */
    @Test fun marksCoverPhotos() = runTest(dispatcher) {
        val admin = FakeAdminRepository(campsites = listOf(
            ModeratedCampsite(Campsite("lake", "jane", "Two Jack Lakeside", "Banff", 6, 3800, photoId = "p3"), true),
            ModeratedCampsite(Campsite("pine", "jane", "Pine Ridge", "Jasper", 4, 2900, photoId = "other"), true),
        ))
        val controller = AdminImagesController(FakeAdminImageRepository(), admin)
        runCurrent()
        assertEquals(listOf(true, false, false), controller.state.value.rows.map { it.cover })
    }

    /** A profile picture is labelled with the profile it belongs to. */
    @Test fun labelsProfilePictures() = runTest(dispatcher) {
        val images = FakeAdminImageRepository(photos = listOf(
            Photo("u1", "jill", "user", "jill", "", "image/jpeg"),
            Photo("u2", "nobody", "user", "nobody", "", "image/jpeg"),
        ))
        val controller = AdminImagesController(images, FakeAdminRepository())
        runCurrent()
        assertEquals(listOf("Profile of Jill Poe", "Unknown profile"), controller.state.value.rows.map { it.placeName })
        assertEquals("Jill Poe", controller.state.value.rows.first().ownerName)
    }

    /** An image of a removed campsite is still listed, so the admin can clean it up. */
    @Test fun listsImagesOfRemovedCampsites() = runTest(dispatcher) {
        val admin = FakeAdminRepository(campsites = listOf(
            ModeratedCampsite(Campsite("lake", "jane", "Two Jack Lakeside", "Banff", 6, 3800), false),
        ))
        val controller = AdminImagesController(FakeAdminImageRepository(), admin)
        runCurrent()
        assertEquals("Two Jack Lakeside", controller.state.value.rows.first().placeName)
    }

    /** Remove asks first; Cancel deletes nothing (US 03.03.01). */
    @Test fun cancelKeepsTheImage() = runTest(dispatcher) {
        val images = FakeAdminImageRepository()
        val controller = AdminImagesController(images, FakeAdminRepository())
        runCurrent()
        controller.requestRemove("p2")
        assertEquals("p2", controller.state.value.confirming?.photo?.id)
        controller.cancelRemove()
        runCurrent()
        assertNull(controller.state.value.confirming)
        assertTrue(images.removed.isEmpty())
        assertEquals(3, controller.state.value.rows.size)
    }

    /** Confirming deletes the image and drops it from the grid (US 03.03.01). */
    @Test fun confirmRemovesTheImage() = runTest(dispatcher) {
        val images = FakeAdminImageRepository()
        val controller = AdminImagesController(images, FakeAdminRepository())
        runCurrent()
        controller.requestRemove("p2")
        controller.confirmRemove()
        runCurrent()
        val state = controller.state.value
        assertEquals(listOf("p2"), images.removed)
        assertEquals(listOf("p3", "p1"), state.rows.map { it.photo.id })
        assertNull(state.confirming)
        assertFalse(state.removing)
        assertEquals("Removed an image from Pine Ridge.", state.result)
    }

    /** A failed delete keeps the image and explains the failure. */
    @Test fun failedRemovalKeepsTheImage() = runTest(dispatcher) {
        val images = FakeAdminImageRepository()
        val controller = AdminImagesController(images, FakeAdminRepository())
        runCurrent()
        images.failure = IllegalStateException("offline")
        controller.requestRemove("p3")
        controller.confirmRemove()
        runCurrent()
        val state = controller.state.value
        assertEquals("Could not remove the image. Check your connection and retry.", state.actionError)
        assertEquals(3, state.rows.size)
        assertNull(state.confirming)
    }

    /** An unknown ID opens no confirmation. */
    @Test fun unknownImageIsIgnored() = runTest(dispatcher) {
        val controller = AdminImagesController(FakeAdminImageRepository(), FakeAdminRepository())
        runCurrent()
        controller.requestRemove("missing")
        assertNull(controller.state.value.confirming)
    }

    /** A failed load shows an error, and Retry recovers. */
    @Test fun failureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val images = FakeAdminImageRepository().apply { failure = IllegalStateException("offline") }
        val controller = AdminImagesController(images, FakeAdminRepository())
        runCurrent()
        assertEquals("Could not load images. Please retry.", controller.state.value.error)
        images.failure = null
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals(3, controller.state.value.rows.size)
    }

    /** No uploads gives an empty, loaded list. */
    @Test fun emptyListLoads() = runTest(dispatcher) {
        val controller = AdminImagesController(FakeAdminImageRepository(photos = emptyList()), FakeAdminRepository())
        runCurrent()
        assertTrue(controller.state.value.rows.isEmpty())
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
    }
}
