/*
 * AdminProfilesControllerTest.kt
 *
 * Purpose: Unit tests for the S19 Profiles tab (US 03.05.01, 03.02.01, 03.07.01).
 * Role: Tests AdminProfilesController with an in-memory repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

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

/** Verifies browsing, searching, and removing profiles and owners as an admin. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminProfilesControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** Every profile is listed with its role; owners are users with active campsites (US 03.05.01). */
    @Test fun listsProfilesWithRoles() = runTest(dispatcher) {
        val controller = AdminProfilesController(FakeAdminRepository())
        runCurrent()
        val roles = controller.state.value.rows.associate { it.profile.uid to it.roleLabel }
        assertEquals(mapOf("boss" to "Admin", "jane" to "Owner", "jill" to "Entrant", "john" to "Entrant"), roles)
        assertEquals(2, controller.state.value.rows.first { it.profile.uid == "jane" }.activeCampsites)
    }

    /** Search matches the name, email, or phone. */
    @Test fun searchFiltersByNameEmailOrPhone() = runTest(dispatcher) {
        val controller = AdminProfilesController(FakeAdminRepository())
        runCurrent()
        controller.setQuery("JANE@")
        assertEquals(listOf("jane"), controller.state.value.shown.map { it.profile.uid })
        controller.setQuery("0100")
        assertEquals(listOf("jill"), controller.state.value.shown.map { it.profile.uid })
    }

    /** Remove profile asks first, then disables only that account (US 03.02.01). */
    @Test fun removeProfileDisablesAccount() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        val controller = AdminProfilesController(admin)
        runCurrent()
        controller.requestRemoveProfile("jill")
        assertEquals(PendingRemoval("jill", "Jill Poe", withCampsites = false), controller.state.value.confirming)
        controller.cancelRemove()
        assertNull(controller.state.value.confirming)
        assertFalse(admin.profiles.first { it.uid == "jill" }.disabled)

        controller.requestRemoveProfile("jill")
        controller.confirmRemove()
        runCurrent()
        assertEquals("Removed Jill Poe.", controller.state.value.result)
        assertTrue(admin.profiles.first { it.uid == "jill" }.disabled)
        assertTrue(controller.state.value.rows.first { it.profile.uid == "jill" }.profile.disabled)
    }

    /** Remove owner disables the account and removes all of the owner's campsites (US 03.07.01). */
    @Test fun removeOwnerAlsoRemovesTheirCampsites() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        val controller = AdminProfilesController(admin)
        runCurrent()
        controller.requestRemoveOwner("jane")
        controller.confirmRemove()
        runCurrent()
        assertEquals("Removed Jane Doe and 2 campsites.", controller.state.value.result)
        assertTrue(admin.profiles.first { it.uid == "jane" }.disabled)
        assertTrue(admin.campsites.filter { it.campsite.ownerId == "jane" }.none { it.active })
        assertEquals(0, controller.state.value.rows.first { it.profile.uid == "jane" }.activeCampsites)
    }

    /** Admins and already removed profiles cannot be removed. */
    @Test fun adminsAndRemovedProfilesAreProtected() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        admin.profiles = admin.profiles.map { if (it.uid == "john") it.copy(disabled = true) else it }
        val controller = AdminProfilesController(admin)
        runCurrent()
        controller.requestRemoveProfile("boss")
        assertNull(controller.state.value.confirming)
        controller.requestRemoveProfile("john")
        assertNull(controller.state.value.confirming)
    }

    /** A failed removal explains why and leaves the account active. */
    @Test fun failedRemovalShowsError() = runTest(dispatcher) {
        val admin = FakeAdminRepository()
        val controller = AdminProfilesController(admin)
        runCurrent()
        controller.requestRemoveProfile("jill")
        admin.failure = IllegalStateException("offline")
        controller.confirmRemove()
        runCurrent()
        assertEquals("Could not remove Jill Poe. Check your connection and retry.", controller.state.value.actionError)
        assertNull(controller.state.value.confirming)
        admin.failure = null
        assertFalse(admin.profiles.first { it.uid == "jill" }.disabled)
    }
}
