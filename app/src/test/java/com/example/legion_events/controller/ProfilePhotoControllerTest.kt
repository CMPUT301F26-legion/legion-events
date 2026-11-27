/*
 * ProfilePhotoControllerTest.kt
 *
 * Purpose: Verify photo loading, replacement, removal, and stable-ID retries without contact changes.
 * Role: Local JVM tests for the focused image controller using an in-memory profile repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Tests explicit image actions with no Firebase or Android bitmap runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val old = Photo("old", "alice", "user", "alice", "jpeg", "image/jpeg")
    private val repository = FakeProfile()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun replacementAndRemovalKeepContactEditsOutOfTheImageController() = runTest(dispatcher) {
        val controller = controller(); runCurrent()
        assertEquals(old, controller.state.value.photo)
        controller.changePhoto { "new-jpeg" }
        testScheduler.advanceUntilIdle()
        assertEquals("new-jpeg", controller.state.value.photo?.imageBase64)
        assertEquals(1, repository.writes)
        controller.removePhoto(); runCurrent()
        assertNull(controller.state.value.photo)
        assertEquals(1, repository.removals)
    }

    @Test fun uncertainSavePreservesOldImageAndRetryUsesTheSameIdWithoutReencoding() = runTest(dispatcher) {
        val controller = controller(); runCurrent()
        repository.fail = true
        var reads = 0
        controller.changePhoto { reads++; "new-jpeg" }
        runCurrent()
        assertEquals(old, controller.state.value.photo)
        assertNotNull(controller.state.value.error)
        val id = repository.ids.single()
        repository.fail = false
        controller.retry(); runCurrent()
        assertEquals(listOf(id, id), repository.ids)
        assertEquals(1, reads)
        assertEquals(id, controller.state.value.photo?.id)
        assertSame(controller, controller())
        assertEquals(2, repository.writes)
    }

    @Test fun unreadableImageDoesNotWriteOrRemoveThePreviousImage() = runTest(dispatcher) {
        val controller = controller(); runCurrent()
        controller.changePhoto { null }
        runCurrent()
        assertEquals(old, controller.state.value.photo)
        assertNotNull(controller.state.value.error)
        assertEquals(0, repository.writes)
    }

    /** Creates a lifecycle-owned controller so looking it up again never retries a save. */
    private fun controller(): ProfilePhotoController = ViewModelProvider.create(store, viewModelFactory {
        initializer { ProfilePhotoController(repository, dispatcher) }
    })[ProfilePhotoController::class]

    /** Captures image operations and rejects accidental contact or account edits. */
    private inner class FakeProfile : ProfileRepository {
        var image: Photo? = old
        var fail = false
        var writes = 0
        var removals = 0
        val ids = mutableListOf<String>()
        override suspend fun getPhoto() = image
        override suspend fun setPhoto(photoId: String, jpegBase64: String, previousPhotoId: String?): Photo {
            writes++; ids += photoId
            if (fail) error("Unavailable")
            return Photo(photoId, "alice", "user", "alice", jpegBase64, "image/jpeg").also { image = it }
        }
        override suspend fun removePhoto(photoId: String) { removals++; image = null }
        override suspend fun getProfile() = UserProfile("alice")
        override suspend fun updateContact(name: String, email: String, phone: String): Unit = error("Unexpected contact edit")
        override suspend fun setNotificationsEnabled(enabled: Boolean): Unit = error("Unexpected preference edit")
        override suspend fun deleteProfile(): Unit = error("Unexpected account delete")
    }
}
