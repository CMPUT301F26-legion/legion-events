package com.example.legion_events.controller

import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsSavedProfileIntoFields() = runTest(dispatcher) {
        val controller = ProfileController(FakeRepository())
        assertTrue(controller.state.value.loading)
        runCurrent()
        with(controller.state.value) {
            assertFalse(loading)
            assertEquals("Alex Morgan", name)
            assertEquals("alex@example.com", email)
            assertEquals("780-555-0142", phone)
            assertFalse(notificationsEnabled)
        }
    }

    @Test
    fun loadFailureCanRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failLoad = true }
        val controller = ProfileController(repository)
        runCurrent()
        assertEquals("Could not load your profile. Check your connection and retry.", controller.state.value.loadError)
        repository.failLoad = false
        controller.retry()
        runCurrent()
        assertNull(controller.state.value.loadError)
        assertEquals("Alex Morgan", controller.state.value.name)
    }

    @Test
    fun savesTrimmedContactDetails() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = ProfileController(repository)
        runCurrent()
        controller.setName("  Sam Lee ")
        controller.setEmail(" sam@example.ca ")
        controller.setPhone("+1 (780) 555-0100")
        controller.save()
        assertTrue(controller.state.value.saving)
        runCurrent()
        assertEquals(Triple("Sam Lee", "sam@example.ca", "+1 (780) 555-0100"), repository.saved)
        assertFalse(controller.state.value.saving)
        assertEquals("Profile saved.", controller.state.value.message)
    }

    @Test
    fun emailAndPhoneAreOptional() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = ProfileController(repository)
        runCurrent()
        controller.setEmail("")
        controller.setPhone(" ")
        controller.save()
        runCurrent()
        assertEquals(Triple("Alex Morgan", "", ""), repository.saved)
    }

    @Test
    fun invalidFieldsAreNotSaved() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = ProfileController(repository)
        runCurrent()
        controller.setName(" ")
        controller.setEmail("not-an-email")
        controller.setPhone("12ab")
        controller.save()
        runCurrent()
        assertNull(repository.saved)
        with(controller.state.value) {
            assertEquals("Enter your name.", nameError)
            assertEquals("Enter a valid email address.", emailError)
            assertEquals("Enter a valid phone number.", phoneError)
        }
        controller.setEmail("a@b.co")
        assertNull(controller.state.value.emailError)
    }

    @Test
    fun saveFailureKeepsEditsAndExplains() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failWrites = true }
        val controller = ProfileController(repository)
        runCurrent()
        controller.setName("Sam Lee")
        controller.save()
        runCurrent()
        assertEquals("Sam Lee", controller.state.value.name)
        assertEquals("Could not save your profile. Check your connection and retry.", controller.state.value.message)
    }

    @Test
    fun optingOutOfNotificationsIsSaved() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = ProfileController(repository)
        runCurrent()
        controller.setNotificationsEnabled(true)
        assertTrue(controller.state.value.notificationsEnabled)
        runCurrent()
        assertEquals(true, repository.notifications)
        controller.setNotificationsEnabled(false)
        runCurrent()
        assertEquals(false, repository.notifications)
        assertFalse(controller.state.value.notificationsEnabled)
    }

    @Test
    fun failedNotificationChangeIsReverted() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failWrites = true }
        val controller = ProfileController(repository)
        runCurrent()
        controller.setNotificationsEnabled(true)
        runCurrent()
        assertFalse(controller.state.value.notificationsEnabled)
        assertEquals("Could not update notifications. Check your connection and retry.", controller.state.value.message)
    }

    @Test
    fun deleteNeedsConfirmation() = runTest(dispatcher) {
        val repository = FakeRepository()
        val controller = ProfileController(repository)
        runCurrent()
        controller.requestDelete()
        assertTrue(controller.state.value.confirmingDelete)
        controller.cancelDelete()
        runCurrent()
        assertFalse(controller.state.value.confirmingDelete)
        assertEquals(0, repository.deletes)

        controller.requestDelete()
        controller.confirmDelete()
        assertTrue(controller.state.value.deleting)
        runCurrent()
        assertEquals(1, repository.deletes)
        assertTrue(controller.state.value.deleted)
    }

    @Test
    fun repeatedDeleteTapsDeleteOnce() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { deleteResult = pending }
        val controller = ProfileController(repository)
        runCurrent()
        repeat(3) { controller.confirmDelete() }
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertEquals(1, repository.deletes)
    }

    @Test
    fun deleteFailureKeepsProfile() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failWrites = true }
        val controller = ProfileController(repository)
        runCurrent()
        controller.confirmDelete()
        runCurrent()
        assertFalse(controller.state.value.deleted)
        assertFalse(controller.state.value.deleting)
        assertEquals("Could not delete your profile. Check your connection and retry.", controller.state.value.message)
    }

    private class FakeRepository : ProfileRepository {
        var failLoad = false
        var failWrites = false
        var saved: Triple<String, String, String>? = null
        var notifications: Boolean? = null
        var deletes = 0
        var deleteResult: CompletableDeferred<Unit>? = null

        override suspend fun getProfile(): UserProfile {
            if (failLoad) error("offline")
            return UserProfile("entrant", "Alex Morgan", "alex@example.com", "780-555-0142", notificationsEnabled = false)
        }

        override suspend fun updateContact(name: String, email: String, phone: String) {
            if (failWrites) error("offline")
            saved = Triple(name, email, phone)
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            if (failWrites) error("offline")
            notifications = enabled
        }

        override suspend fun deleteProfile() {
            deletes++
            deleteResult?.await()
            if (failWrites) error("offline")
        }
    }
}
