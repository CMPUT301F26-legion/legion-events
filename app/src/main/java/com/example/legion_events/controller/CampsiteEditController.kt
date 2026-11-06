/*
 * CampsiteEditController.kt
 *
 * Purpose: Holds the S13 add/edit campsite form, validates it, and saves the campsite and cover photo (US 02.01.01 to 02.01.03).
 * Role: Controller in MVC; CampsiteEditScreen renders its state and OwnerCampsiteRepository saves it.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.CoverPhotoNotSavedException
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.model.Campsite
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Amenities offered as chips on S13, in mockup order. */
val CAMPSITE_AMENITIES = listOf(
    "Fire pit", "Picnic table", "Flush toilets", "Showers", "Power hookup", "Potable water", "Pet friendly",
)

/** Cancellation policies offered on S13. */
val CANCELLATION_POLICIES = listOf(
    "Full refund up to 7 days before",
    "Full refund up to 48 hours before",
    "50% refund up to 7 days before",
    "No refunds",
)

/** The most nights one listing can cover, so a mistyped year cannot create a huge document. */
internal const val MAX_LISTED_NIGHTS = 366

/** The most guests and the highest nightly price (CAD) accepted on S13. */
private const val MAX_GUESTS = 100
/** The highest nightly price in CAD. */
private val MAX_PRICE = BigDecimal("100000")
/** Longest name, location, description, and rules text the Firestore rules accept. */
private const val MAX_NAME = 150
/** Longest area or town. */
private const val MAX_LOCATION = 250
/** Longest description, and longest rules text. */
private const val MAX_LONG_TEXT = 10_000
/** The longest tag accepted. */
private const val MAX_TAG_LENGTH = 30
/** The most tags accepted. */
private const val MAX_TAGS = 15

/**
 * Fields on S13 that can show an inline error, in the order they appear on the form.
 * @property label The name used in the summary shown when saving fails.
 */
enum class CampsiteField(val label: String) {
    /** Campsite name. */
    NAME("Campsite name"),
    /** Area or town. */
    LOCATION("Location"),
    /** Description. */
    DESCRIPTION("Description"),
    /** Maximum guests. */
    GUESTS("Max occupancy"),
    /** Nightly price. */
    PRICE("Price per night"),
    /** Latitude and longitude. */
    COORDINATES("Map coordinates"),
    /** New tag text. */
    TAG("Tags"),
    /** Campsite rules. */
    RULES("Rules"),
    /** Cancellation policy. */
    CANCELLATION("Cancellation policy"),
    /** Open-from and open-until dates. */
    AVAILABILITY("Availability"),
}

/**
 * What the add/edit campsite form (S13) displays.
 * @property campsiteId The campsite being edited, or null when adding one.
 * @property loading Whether an edited campsite is still loading.
 * @property loadError Why the campsite could not be loaded, or null.
 * @property name Campsite name field.
 * @property locationName Area or town field.
 * @property description Description field.
 * @property amenities Selected amenities.
 * @property amenityOptions Amenity chips to show, including any saved ones not in [CAMPSITE_AMENITIES].
 * @property tags Added tags.
 * @property tagInput The tag being typed.
 * @property maxGuests Maximum occupancy field.
 * @property price Price per night field, in dollars.
 * @property latitude Latitude field.
 * @property longitude Longitude field.
 * @property locating Whether the device location is being read.
 * @property locationMessage Why the device location could not be used, or null.
 * @property rules Rules field; each line is saved as one rule.
 * @property cancellationPolicy Selected cancellation policy.
 * @property cancellationOptions Policies to offer, including a saved custom one.
 * @property openFrom First listed night, `YYYY-MM-DD`, or empty.
 * @property openUntil Last listed night, `YYYY-MM-DD`, or empty.
 * @property nightsHadGaps Whether the edited listing had gaps that saving will fill.
 * @property newPhoto A newly picked Base64 JPEG cover photo, or null.
 * @property hasCover Whether the edited listing already has a cover photo.
 * @property encodingPhoto Whether a picked photo is being compressed.
 * @property photoMessage Why the picked photo could not be used, or null.
 * @property errors Inline errors by field.
 * @property failedSaves Counts saves rejected by validation, so the screen scrolls to the first error each time.
 * @property saving Whether the campsite is being saved.
 * @property message A save failure to show, or null.
 * @property savedCampsiteId The saved campsite's ID once saving succeeds, so the screen can leave.
 */
data class CampsiteEditState(
    val campsiteId: String? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    val name: String = "",
    val locationName: String = "",
    val description: String = "",
    val amenities: Set<String> = emptySet(),
    val amenityOptions: List<String> = CAMPSITE_AMENITIES,
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val maxGuests: String = "",
    val price: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val locating: Boolean = false,
    val locationMessage: String? = null,
    val rules: String = "",
    val cancellationPolicy: String = "",
    val cancellationOptions: List<String> = CANCELLATION_POLICIES,
    val openFrom: String = "",
    val openUntil: String = "",
    val nightsHadGaps: Boolean = false,
    val newPhoto: String? = null,
    val hasCover: Boolean = false,
    val encodingPhoto: Boolean = false,
    val photoMessage: String? = null,
    val errors: Map<CampsiteField, String> = emptyMap(),
    val failedSaves: Int = 0,
    val saving: Boolean = false,
    val message: String? = null,
    val savedCampsiteId: String? = null,
) {
    /** Whether this form edits an existing campsite. */
    val editing: Boolean get() = campsiteId != null
}

/**
 * Handles the add/edit campsite form (S13): prefilling, validation, and saving.
 * @property campsiteId The campsite to edit, or null to add one.
 * @property repository Reads and saves the owner's campsites.
 */
class CampsiteEditController(
    private val campsiteId: String?,
    private val repository: OwnerCampsiteRepository,
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(CampsiteEditState(campsiteId = campsiteId, loading = campsiteId != null))
    /** Read-only state collected by the form. */
    val state = mutableState.asStateFlow()
    /** Current load request, cancelled before retrying. */
    private var request: Job? = null
    /** The listing being edited, kept so unchanged fields such as ratings are not lost. */
    private var original: Campsite? = null

    init { if (campsiteId != null) retry() }

    /** Reloads the edited campsite, discarding unsaved changes. Does nothing when adding. */
    fun retry() {
        val id = campsiteId ?: return
        request?.cancel()
        mutableState.value = CampsiteEditState(campsiteId = id, loading = true)
        request = viewModelScope.launch {
            mutableState.value = try {
                val site = repository.getMyCampsite(id)
                if (site == null) CampsiteEditState(campsiteId = id, loadError = "This campsite is no longer available.")
                else { original = site; prefill(site) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CampsiteEditState(campsiteId = id, loadError = "Could not load this campsite. Check your connection and retry.")
            }
        }
    }

    /**
     * Copies a saved listing into the form.
     * @param site The saved listing.
     * @return The prefilled form state.
     */
    private fun prefill(site: Campsite): CampsiteEditState {
        val nights = site.availableNights.sorted()
        return CampsiteEditState(
            campsiteId = site.id,
            name = site.name,
            locationName = site.locationName,
            description = site.description,
            amenities = site.amenities.toSet(),
            amenityOptions = (CAMPSITE_AMENITIES + site.amenities).distinct(),
            tags = site.tags,
            maxGuests = site.maxGuests.takeIf { it > 0 }?.toString().orEmpty(),
            price = if (site.pricePerNight > 0) BigDecimal.valueOf(site.pricePerNight, 2).toPlainString() else "",
            latitude = site.location?.latitude?.toString().orEmpty(),
            longitude = site.location?.longitude?.toString().orEmpty(),
            rules = site.rules.joinToString("\n"),
            cancellationPolicy = site.cancellationPolicy,
            cancellationOptions = (CANCELLATION_POLICIES + site.cancellationPolicy).filter(String::isNotBlank).distinct(),
            openFrom = nights.firstOrNull().orEmpty(),
            openUntil = nights.lastOrNull().orEmpty(),
            nightsHadGaps = nights.isNotEmpty() && nightsBetween(nights.first(), nights.last()) != nights,
            hasCover = site.photoId != null,
        )
    }

    /**
     * Applies a field change, clearing that field's error and any save message.
     * @param field The field whose error is cleared, or null.
     * @param change Builds the new state.
     */
    private fun edit(field: CampsiteField?, change: CampsiteEditState.() -> CampsiteEditState) {
        val current = state.value
        if (current.saving) return
        mutableState.value = current.change().copy(errors = current.errors - setOfNotNull(field), message = null)
    }

    /** Updates the name. */
    fun setName(value: String) = edit(CampsiteField.NAME) { copy(name = value) }

    /** Updates the area or town. */
    fun setLocationName(value: String) = edit(CampsiteField.LOCATION) { copy(locationName = value) }

    /** Updates the description. */
    fun setDescription(value: String) = edit(null) { copy(description = value) }

    /** Selects or clears an amenity chip. */
    fun toggleAmenity(amenity: String) = edit(null) {
        copy(amenities = if (amenity in amenities) amenities - amenity else amenities + amenity)
    }

    /** Updates the tag being typed. */
    fun setTagInput(value: String) = edit(CampsiteField.TAG) { copy(tagInput = value) }

    /** Adds the typed tag, ignoring duplicates regardless of case. */
    fun addTag() {
        val current = state.value
        val tag = current.tagInput.trim()
        val error = when {
            tag.isEmpty() -> return
            tag.length > MAX_TAG_LENGTH -> "Keep tags under $MAX_TAG_LENGTH characters."
            current.tags.size >= MAX_TAGS -> "You can add up to $MAX_TAGS tags."
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(errors = current.errors + (CampsiteField.TAG to error))
            return
        }
        edit(CampsiteField.TAG) {
            copy(tagInput = "", tags = if (tags.any { it.equals(tag, ignoreCase = true) }) tags else tags + tag)
        }
    }

    /** Removes a tag. */
    fun removeTag(tag: String) = edit(null) { copy(tags = tags - tag) }

    /** Updates the maximum occupancy. */
    fun setMaxGuests(value: String) = edit(CampsiteField.GUESTS) { copy(maxGuests = value) }

    /** Updates the nightly price. */
    fun setPrice(value: String) = edit(CampsiteField.PRICE) { copy(price = value) }

    /** Updates the latitude. */
    fun setLatitude(value: String) = edit(CampsiteField.COORDINATES) { copy(latitude = value, locationMessage = null) }

    /** Updates the longitude. */
    fun setLongitude(value: String) = edit(CampsiteField.COORDINATES) { copy(longitude = value, locationMessage = null) }

    /** Shows that the device location is being read. */
    fun startLocating() = edit(null) { copy(locating = true, locationMessage = null) }

    /**
     * Fills the coordinates from the device location.
     * @param latitude Degrees north.
     * @param longitude Degrees east.
     */
    fun setCurrentLocation(latitude: Double, longitude: Double) = edit(CampsiteField.COORDINATES) {
        copy(latitude = "%.4f".format(Locale.US, latitude), longitude = "%.4f".format(Locale.US, longitude),
            locating = false, locationMessage = null)
    }

    /**
     * Reports that the device location could not be used.
     * @param message What the user can do instead.
     */
    fun locationFailed(message: String) = edit(null) { copy(locating = false, locationMessage = message) }

    /** Updates the rules. */
    fun setRules(value: String) = edit(CampsiteField.RULES) { copy(rules = value) }

    /** Selects a cancellation policy. */
    fun setCancellationPolicy(value: String) = edit(CampsiteField.CANCELLATION) { copy(cancellationPolicy = value) }

    /** Sets the first listed night (`YYYY-MM-DD`). */
    fun setOpenFrom(day: String) = edit(CampsiteField.AVAILABILITY) { copy(openFrom = day) }

    /** Sets the last listed night (`YYYY-MM-DD`). */
    fun setOpenUntil(day: String) = edit(CampsiteField.AVAILABILITY) { copy(openUntil = day) }

    /** Shows that a picked photo is being compressed. */
    fun startPhoto() = edit(null) { copy(encodingPhoto = true, photoMessage = null) }

    /**
     * Uses a compressed photo as the new cover, or reports that it could not be read.
     * @param base64Jpeg The compressed photo, or null if it was unreadable.
     */
    fun setPhoto(base64Jpeg: String?) = edit(null) {
        if (base64Jpeg == null) copy(encodingPhoto = false, photoMessage = "Could not use that image. Choose a JPG or PNG photo.")
        else copy(encodingPhoto = false, newPhoto = base64Jpeg, photoMessage = null)
    }

    /** Discards a newly picked photo. */
    fun removePhoto() = edit(null) { copy(newPhoto = null, photoMessage = null) }

    /** Validates the form and saves the campsite when every field is valid. */
    fun save() {
        val current = state.value
        if (current.loading || current.loadError != null || current.saving || current.encodingPhoto) return
        val errors = mutableMapOf<CampsiteField, String>()
        val name = current.name.trim()
        val locationName = current.locationName.trim()
        val description = current.description.trim()
        when {
            name.isEmpty() -> errors[CampsiteField.NAME] = "Enter a campsite name."
            name.length > MAX_NAME -> errors[CampsiteField.NAME] = "Keep the name under $MAX_NAME characters."
        }
        when {
            locationName.isEmpty() -> errors[CampsiteField.LOCATION] = "Enter the area or town."
            locationName.length > MAX_LOCATION -> errors[CampsiteField.LOCATION] = "Keep the location under $MAX_LOCATION characters."
        }
        if (description.length > MAX_LONG_TEXT) errors[CampsiteField.DESCRIPTION] = "Keep the description under $MAX_LONG_TEXT characters."

        val guests = current.maxGuests.trim().toIntOrNull()
        if (guests == null || guests !in 1..MAX_GUESTS) errors[CampsiteField.GUESTS] = "Enter 1 to $MAX_GUESTS guests."

        val cents = parsePriceCents(current.price)
        if (cents == null) errors[CampsiteField.PRICE] = "Enter a price such as 38.00."

        val location = parseCoordinates(current.latitude, current.longitude)
        if (location.isFailure) errors[CampsiteField.COORDINATES] = location.exceptionOrNull()?.message.orEmpty()

        val rules = current.rules.lines().map(String::trim).filter(String::isNotEmpty)
        when {
            rules.isEmpty() -> errors[CampsiteField.RULES] = "Enter at least one rule."
            rules.joinToString("\n").length > MAX_LONG_TEXT -> errors[CampsiteField.RULES] = "Keep the rules under $MAX_LONG_TEXT characters."
        }
        if (current.cancellationPolicy.isBlank()) errors[CampsiteField.CANCELLATION] = "Choose a cancellation policy."

        val nights = when {
            current.openFrom.isEmpty() || current.openUntil.isEmpty() -> null.also {
                errors[CampsiteField.AVAILABILITY] = "Choose when the campsite opens and closes."
            }
            current.openUntil < current.openFrom -> null.also {
                errors[CampsiteField.AVAILABILITY] = "Open until must be on or after open from."
            }
            else -> nightsBetween(current.openFrom, current.openUntil).also {
                if (it.size > MAX_LISTED_NIGHTS) errors[CampsiteField.AVAILABILITY] = "List at most $MAX_LISTED_NIGHTS nights at once."
            }
        }

        if (errors.isNotEmpty()) {
            val names = errors.keys.sorted().joinToString(", ") { it.label }
            mutableState.value = current.copy(errors = errors, failedSaves = current.failedSaves + 1,
                message = "Not saved yet. Fix ${if (errors.size == 1) "this field" else "these fields"}: $names.")
            return
        }
        val base = original
        val campsite = Campsite(
            // A campsite created by an earlier save whose photo failed is updated, not created again.
            id = current.campsiteId.orEmpty(),
            ownerId = base?.ownerId.orEmpty(),
            name = name,
            locationName = locationName,
            maxGuests = checkNotNull(guests),
            pricePerNight = checkNotNull(cents),
            description = description,
            amenities = current.amenityOptions.filter { it in current.amenities },
            tags = current.tags,
            location = checkNotNull(location.getOrNull()),
            rules = rules,
            cancellationPolicy = current.cancellationPolicy,
            availableNights = checkNotNull(nights),
            photoId = base?.photoId,
        )
        mutableState.value = current.copy(saving = true, errors = emptyMap(), message = null)
        viewModelScope.launch {
            mutableState.value = try {
                val id = repository.saveCampsite(campsite, current.newPhoto)
                state.value.copy(saving = false, savedCampsiteId = id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CoverPhotoNotSavedException) {
                // Keep the form open on the saved campsite so Save retries only the photo.
                state.value.copy(saving = false, campsiteId = error.campsiteId,
                    message = "Campsite saved, but the photo could not be uploaded. Tap Save to try the photo again.")
            } catch (_: Exception) {
                state.value.copy(saving = false, message = "Could not save this campsite. Check your connection and retry.")
            }
        }
    }
}

/**
 * Reads a dollar price into integer CAD cents without floating-point rounding.
 * @param text The price field, optionally starting with `$`.
 * @return Cents, or null if the price is missing, negative, too precise, or too high.
 */
internal fun parsePriceCents(text: String): Long? = try {
    val price = BigDecimal(text.trim().removePrefix("$").trim())
    if (price.signum() < 0 || price > MAX_PRICE || price.stripTrailingZeros().scale() > 2) null
    else price.movePointRight(2).longValueExact()
} catch (_: NumberFormatException) {
    null
} catch (_: ArithmeticException) {
    null
}

/**
 * Reads the required coordinates; the Firestore rules reject a campsite without them.
 * @param latitude The latitude field.
 * @param longitude The longitude field.
 * @return The point, or a failure explaining the problem.
 */
internal fun parseCoordinates(latitude: String, longitude: String): Result<GeoPoint> {
    val lat = latitude.trim()
    val lon = longitude.trim()
    if (lat.isEmpty() || lon.isEmpty()) {
        return Result.failure(IllegalArgumentException("Enter latitude and longitude, or tap Use current location."))
    }
    val latValue = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        ?: return Result.failure(IllegalArgumentException("Latitude must be between -90 and 90."))
    val lonValue = lon.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        ?: return Result.failure(IllegalArgumentException("Longitude must be between -180 and 180."))
    return Result.success(GeoPoint(latValue, lonValue))
}

/** DatePicker works in UTC milliseconds, so night dates are converted in UTC. */
internal fun utcDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}

/**
 * Lists every night from [first] to [last], inclusive.
 * Stops after [MAX_LISTED_NIGHTS] + 1 nights so callers can reject a range that is too long.
 * @param first The first night, `YYYY-MM-DD`.
 * @param last The last night, `YYYY-MM-DD`.
 * @return The nights in order, or an empty list for an unreadable or reversed range.
 */
internal fun nightsBetween(first: String, last: String): List<String> {
    val format = utcDayFormat()
    val start = try { format.parse(first)?.time } catch (_: ParseException) { null } ?: return emptyList()
    val end = try { format.parse(last)?.time } catch (_: ParseException) { null } ?: return emptyList()
    val day = 24 * 60 * 60 * 1000L
    return generateSequence(start) { it + day }.takeWhile { it <= end }.take(MAX_LISTED_NIGHTS + 1)
        .map(format::format).toList()
}
