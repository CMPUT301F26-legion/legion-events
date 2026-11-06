/*
 * CampsiteEditControllerTest.kt
 *
 * Purpose: Unit tests for CampsiteEditController form validation and saving (US 02.01.01 to 02.01.03).
 * Role: Uses FakeOwnerCampsiteRepository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.CoverPhotoNotSavedException
import com.example.legion_events.model.Campsite
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampsiteEditControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val saved = Campsite(
        id = "lake", ownerId = "owner", name = "Two Jack Lakeside", locationName = "Banff National Park, AB",
        maxGuests = 6, pricePerNight = 3850, description = "Lake views", amenities = listOf("Fire pit", "Canoe launch"),
        tags = listOf("Lakeside"), location = GeoPoint(51.2207, -115.4927), rules = listOf("Quiet after 11 pm", "Max 2 vehicles"),
        cancellationPolicy = "Custom policy", availableNights = listOf("2027-06-01", "2027-06-02", "2027-06-04"),
        photoId = "cover", ratingAverage = 4.5, ratingCount = 3,
    )

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun blankFormShowsRequiredErrorsAndDoesNotSave() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository()
        val controller = controller(null, repository)
        controller.save()
        runCurrent()
        val errors = controller.state.value.errors.keys
        assertEquals(setOf(CampsiteField.NAME, CampsiteField.LOCATION, CampsiteField.GUESTS, CampsiteField.PRICE,
            CampsiteField.COORDINATES, CampsiteField.RULES, CampsiteField.CANCELLATION, CampsiteField.AVAILABILITY), errors)
        assertTrue(repository.saved.isEmpty())
        assertEquals(1, controller.state.value.failedSaves)
        assertEquals("Not saved yet. Fix these fields: Campsite name, Location, Max occupancy, Price per night, " +
            "Map coordinates, Rules, Cancellation policy, Availability.", controller.state.value.message)
        controller.save()
        assertEquals(2, controller.state.value.failedSaves)
        controller.setName("Pine")
        assertFalse(CampsiteField.NAME in controller.state.value.errors)
    }

    @Test fun validNewCampsiteIsSavedAndReportsItsId() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository()
        val controller = controller(null, repository)
        fill(controller)
        controller.toggleAmenity("Potable water")
        controller.toggleAmenity("Fire pit")
        controller.setPhoto("jpeg-data")
        controller.save()
        assertTrue(controller.state.value.saving)
        runCurrent()
        assertEquals("new-site", controller.state.value.savedCampsiteId)
        val (site, photo) = repository.saved.single()
        assertEquals("", site.id)
        assertEquals("Two Jack Lakeside", site.name)
        assertEquals(6, site.maxGuests)
        assertEquals(3850L, site.pricePerNight)
        assertEquals(listOf("Fire pit", "Potable water"), site.amenities)
        assertEquals(listOf("Quiet hours 11 pm to 7 am", "No generators"), site.rules)
        assertEquals(GeoPoint(51.2207, -115.4927), site.location)
        assertEquals(listOf("2027-06-01", "2027-06-02", "2027-06-03"), site.availableNights)
        assertEquals("jpeg-data", photo)
    }

    @Test fun invalidNumbersCoordinatesAndDatesShowInlineErrors() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository()
        val controller = controller(null, repository)
        fill(controller)
        controller.setMaxGuests("0")
        controller.setPrice("12.345")
        controller.setLongitude("")
        controller.setOpenUntil("2027-05-01")
        controller.save()
        val errors = controller.state.value.errors
        assertEquals(setOf(CampsiteField.GUESTS, CampsiteField.PRICE, CampsiteField.COORDINATES, CampsiteField.AVAILABILITY), errors.keys)
        assertEquals("Enter latitude and longitude, or tap Use current location.", errors[CampsiteField.COORDINATES])
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun editingPrefillsAndKeepsCustomValues() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository(campsites = { listOf(saved) })
        val controller = controller("lake", repository)
        assertTrue(controller.state.value.loading)
        runCurrent()
        val state = controller.state.value
        assertTrue(state.editing)
        assertEquals("38.50", state.price)
        assertEquals("Quiet after 11 pm\nMax 2 vehicles", state.rules)
        assertTrue("Canoe launch" in state.amenityOptions)
        assertTrue("Custom policy" in state.cancellationOptions)
        assertEquals("2027-06-01" to "2027-06-04", state.openFrom to state.openUntil)
        assertTrue(state.nightsHadGaps)
        assertTrue(state.hasCover)

        controller.save()
        runCurrent()
        val (site, photo) = repository.saved.single()
        assertEquals("lake", site.id)
        assertEquals(listOf("Fire pit", "Canoe launch"), site.amenities)
        assertEquals(4, site.availableNights.size)
        assertNull(photo)
        assertEquals("lake", controller.state.value.savedCampsiteId)
    }

    @Test fun missingCampsiteCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository()
        val controller = controller("gone", repository)
        runCurrent()
        assertEquals("This campsite is no longer available.", controller.state.value.loadError)
        controller.save()
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun saveFailureKeepsFormAndShowsMessage() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository(save = { _, _ -> error("Offline") })
        val controller = controller(null, repository)
        fill(controller)
        controller.save()
        runCurrent()
        assertFalse(controller.state.value.saving)
        assertNull(controller.state.value.savedCampsiteId)
        assertNotNull(controller.state.value.message)
        assertEquals("  Two Jack Lakeside ", controller.state.value.name)
    }

    @Test fun failedPhotoKeepsFormOnSavedCampsiteSoRetryUpdatesIt() = runTest(dispatcher) {
        var photoFails = true
        val repository = FakeOwnerCampsiteRepository(save = { site, _ ->
            if (photoFails) throw CoverPhotoNotSavedException("created", IllegalStateException("Denied"))
            site.id
        })
        val controller = controller(null, repository)
        fill(controller)
        controller.setPhoto("jpeg-data")
        controller.save()
        runCurrent()
        assertNull(controller.state.value.savedCampsiteId)
        assertEquals("created", controller.state.value.campsiteId)
        assertTrue(controller.state.value.message!!.startsWith("Campsite saved"))

        photoFails = false
        controller.save()
        runCurrent()
        assertEquals(listOf("", "created"), repository.saved.map { it.first.id })
        assertEquals("jpeg-data", repository.saved.last().second)
        assertEquals("created", controller.state.value.savedCampsiteId)
    }

    @Test fun overlongTextIsRejectedBeforeFirestore() = runTest(dispatcher) {
        val repository = FakeOwnerCampsiteRepository()
        val controller = controller(null, repository)
        fill(controller)
        controller.setName("x".repeat(151))
        controller.setDescription("y".repeat(10_001))
        controller.save()
        assertEquals(setOf(CampsiteField.NAME, CampsiteField.DESCRIPTION), controller.state.value.errors.keys)
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun tagsIgnoreDuplicatesAndCanBeRemoved() = runTest(dispatcher) {
        val controller = controller(null, FakeOwnerCampsiteRepository())
        controller.setTagInput(" Lakeside ")
        controller.addTag()
        controller.setTagInput("lakeside")
        controller.addTag()
        controller.setTagInput("Family")
        controller.addTag()
        assertEquals(listOf("Lakeside", "Family"), controller.state.value.tags)
        assertEquals("", controller.state.value.tagInput)
        controller.removeTag("Lakeside")
        assertEquals(listOf("Family"), controller.state.value.tags)
    }

    @Test fun currentLocationFillsRoundedCoordinates() = runTest(dispatcher) {
        val controller = controller(null, FakeOwnerCampsiteRepository())
        controller.startLocating()
        assertTrue(controller.state.value.locating)
        controller.setCurrentLocation(51.220712, -115.492731)
        assertEquals("51.2207" to "-115.4927", controller.state.value.latitude to controller.state.value.longitude)
        assertFalse(controller.state.value.locating)
    }

    @Test fun priceAndNightHelpers() {
        assertEquals(3800L, parsePriceCents("$38"))
        assertEquals(3850L, parsePriceCents("38.50"))
        assertEquals(0L, parsePriceCents("0"))
        assertNull(parsePriceCents("-1"))
        assertNull(parsePriceCents("1.005"))
        assertNull(parsePriceCents("abc"))
        assertNull(parsePriceCents("100000.01"))
        assertEquals(listOf("2027-02-28", "2027-03-01"), nightsBetween("2027-02-28", "2027-03-01"))
        assertEquals(MAX_LISTED_NIGHTS + 1, nightsBetween("2027-01-01", "2029-01-01").size)
        assertTrue(nightsBetween("2027-02-30", "2027-03-01").isEmpty())
        assertTrue(parseCoordinates("", "").isFailure)
        assertEquals(GeoPoint(1.0, 2.0), parseCoordinates(" 1 ", "2").getOrThrow())
        assertTrue(parseCoordinates("91", "0").isFailure)
    }

    /** Fills every required field with valid values. */
    private fun fill(controller: CampsiteEditController) {
        controller.setName("  Two Jack Lakeside ")
        controller.setLocationName("Banff National Park, AB")
        controller.setMaxGuests("6")
        controller.setPrice("$38.50")
        controller.setLatitude("51.2207")
        controller.setLongitude("-115.4927")
        controller.setRules("Quiet hours 11 pm to 7 am\n\n No generators ")
        controller.setCancellationPolicy(CANCELLATION_POLICIES.first())
        controller.setOpenFrom("2027-06-01")
        controller.setOpenUntil("2027-06-03")
    }

    /** Creates an entry-scoped controller. */
    private fun controller(id: String?, repository: FakeOwnerCampsiteRepository): CampsiteEditController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { CampsiteEditController(id, repository) }
        })[CampsiteEditController::class]
}
