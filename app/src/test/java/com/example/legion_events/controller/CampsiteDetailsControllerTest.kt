package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampsiteDetailsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val site = Campsite("pine", "alice", "Pine Ridge", "Banff", 6, 6500, photoId = "cover")
    private val photo = Photo("cover", "alice", "campsite", "pine", "invalid", "image/jpeg")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun loadingWaitsForListingThenLoadsOnlyItsPhotos() = runTest(dispatcher) {
        val pending = CompletableDeferred<Campsite?>()
        val repository = FakeDetailsRepository(load = { pending.await() })
        val controller = controller(repository)
        runCurrent()
        assertTrue(controller.state.value.loading)
        assertNull(controller.state.value.campsite)
        assertEquals(0, repository.photoCalls)
        pending.complete(site)
        runCurrent()
        assertEquals(site, controller.state.value.campsite)
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(photo), controller.state.value.photos)
        assertEquals("pine" to "cover", repository.photoRequest)
    }

    @Test fun missingCampsiteDoesNotRequestPhotos() = runTest(dispatcher) {
        val repository = FakeDetailsRepository(load = { null })
        val controller = controller(repository)
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.campsite)
        assertNull(controller.state.value.error)
        assertEquals(0, repository.photoCalls)
    }

    @Test fun failedListingCanBeRetried() = runTest(dispatcher) {
        var fail = true
        val repository = FakeDetailsRepository(load = { if (fail) error("Unavailable") else site })
        val controller = controller(repository)
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertEquals(0, repository.photoCalls)
        fail = false
        controller.retry()
        runCurrent()
        assertEquals(site, controller.state.value.campsite)
        assertNull(controller.state.value.error)
        assertEquals(2, repository.listingCalls)
    }

    @Test fun photoRetryKeepsTheListingAndDoesNotReloadIt() = runTest(dispatcher) {
        var fail = true
        val repository = FakeDetailsRepository(photos = { if (fail) error("Denied") else listOf(photo) })
        val controller = controller(repository)
        runCurrent()
        assertEquals(site, controller.state.value.campsite)
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.photosError)
        fail = false
        controller.retryPhotos()
        runCurrent()
        assertNull(controller.state.value.photosError)
        assertEquals(listOf(photo), controller.state.value.photos)
        assertEquals(1, repository.listingCalls)
        assertEquals(2, repository.photoCalls)
    }

    @Test fun retriesAndLeavingTheScreenCancelPreviousRequests() = runTest(dispatcher) {
        var active = 0
        val repository = FakeDetailsRepository(load = {
            active++
            try { awaitCancellation() } finally { active-- }
        })
        val controller = controller(repository)
        runCurrent()
        repeat(4) { controller.retry() }
        runCurrent()
        assertEquals(1, active)
        store.clear()
        runCurrent()
        assertEquals(0, active)
    }

    /** Creates an entry-scoped controller so tests also exercise cancellation when its owner is cleared. */
    private fun controller(repository: CampsiteDetailsRepository): CampsiteDetailsController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { CampsiteDetailsController("pine", repository) }
        })[CampsiteDetailsController::class]

    /** Supplies controlled responses without connecting to Firebase. */
    private inner class FakeDetailsRepository(
        private val load: suspend () -> Campsite? = { site },
        private val photos: suspend () -> List<Photo> = { listOf(photo) },
    ) : CampsiteDetailsRepository {
        var listingCalls = 0
        var photoCalls = 0
        var photoRequest: Pair<String, String?>? = null

        /** Records the requested document before returning the selected test response. */
        override suspend fun getCampsite(campsiteId: String): Campsite? {
            assertEquals("pine", campsiteId)
            listingCalls++
            return load()
        }

        /** Records photo association and cover selection before returning the test response. */
        override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?): List<Photo> {
            photoCalls++
            photoRequest = campsiteId to coverPhotoId
            return photos()
        }
    }
}
