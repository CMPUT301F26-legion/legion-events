/*
 * CampsiteListController.kt
 *
 * Purpose: Searches and filters active campsites for S02/S03 (US 01.01.01, 01.01.03, 01.01.04).
 * Role: Controller in MVC; HomeScreen renders its state and CampsiteRepository streams the listings.
 * Outstanding issues: Location and date filters (US 01.01.02) are a later part.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.model.nightFormat
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.model.Campsite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

/** Optional guest-capacity or nightly-price filter. */
enum class CampsiteFilter { ALL, GROUPS, BUDGET }

/**
 * Data displayed by the campsite search screen.
 * @property campsites Active listings returned by Firestore.
 * @property query Search words entered by the user.
 * @property filter Selected guest-capacity or price filter.
 * @property loading Whether the initial result is pending.
 * @property error Request failure message, or null.
 */
data class CampsiteListState(
    val campsites: List<Campsite> = emptyList(),
    val query: String = "",
    val filter: CampsiteFilter = CampsiteFilter.ALL,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** Listings matching every search word and the selected filter. Prices are compared in cents. */
    val visible: List<Campsite> get() {
        val words = normalized(query).split(Regex("\\s+")).filter(String::isNotBlank)
        return campsites.filter { site ->
            val text = normalized(listOf(site.name, site.locationName, site.description,
                site.amenities.joinToString(" "), site.tags.joinToString(" ")).joinToString(" "))
            words.all { it in text } && when (filter) {
                CampsiteFilter.ALL -> true
                CampsiteFilter.GROUPS -> site.maxGuests >= 4
                CampsiteFilter.BUDGET -> site.pricePerNight <= 10_000
            }
        }
    }
}

/**
 * Returns whether a campsite's capacity is strictly greater than a guest count.
 * @param campsite The listing; its [Campsite.maxGuests] is compared.
 * @param guests The guest count to compare against.
 * @return True if `maxGuests > guests`.
 */
fun campsiteGuestMoreThan(campsite: Campsite, guests: Int): Boolean = campsite.maxGuests > guests

/**
 * Returns whether a campsite's capacity is strictly less than a guest count.
 * @param campsite The listing; its [Campsite.maxGuests] is compared.
 * @param guests The guest count to compare against.
 * @return True if `maxGuests < guests`.
 */
fun campsiteGuestLessThan(campsite: Campsite, guests: Int): Boolean = campsite.maxGuests < guests

/** Removes case and accent differences when matching search words. */
private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

/**
 * Loads active listings and retains the user's search through screen recreation.
 * @property repository Reads campsite data.
 * @property savedState Stores the search text and selected filter.
 */
class CampsiteListController(
    private val repository: CampsiteRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Setup and search state changed only by this controller. */
    private val mutableState = MutableStateFlow(CampsiteListState(
        query = savedState["query"] ?: "",
        filter = runCatching { CampsiteFilter.valueOf(savedState["filter"] ?: "ALL") }
            .getOrDefault(CampsiteFilter.ALL),
    ))
    /** Read-only state for the screen. */
    val state = mutableState.asStateFlow()
    /** Current request; cancelled before Retry attaches a replacement listener. */
    private var listener: Job? = null

    init { retry() }

    /** Updates and saves the search text without starting another database request. */
    fun search(query: String) {
        savedState["query"] = query
        mutableState.value = state.value.copy(query = query)
    }

    /** Applies and saves the selected filter. */
    fun filter(filter: CampsiteFilter) {
        savedState["filter"] = filter.name
        mutableState.value = state.value.copy(filter = filter)
    }

    /**
     * Returns whether a loaded active campsite is offered on one night.
     * Checks the owner's `availableNights` only; nights already held by bookings are not checked.
     * @param date A night in `YYYY-MM-DD` format, e.g. "2026-12-04".
     * @param campsiteId The campsite document ID.
     * @return True if the campsite is loaded and lists [date]; false if it does not, or is not loaded.
     * @throws IllegalArgumentException If [date] is not a real `YYYY-MM-DD` date.
     */
    fun filterByDate(date: String, campsiteId: String): Boolean {
        require(isNightKey(date)) { "Use a YYYY-MM-DD date." }
        return state.value.campsites.find { it.id == campsiteId }?.availableNights?.contains(date) == true
    }

    /** Reloads listings after a failure, keeping only one active listener. */
    fun retry() {
        listener?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        listener = viewModelScope.launch {
            try {
                repository.observeCampsites().collect {
                    mutableState.value = state.value.copy(campsites = it, loading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(loading = false,
                    error = error.message ?: "Could not load campsites. Please retry.")
            }
        }
    }
}

/** True for a real calendar date written exactly as `YYYY-MM-DD`, matching stored night keys. */
private fun isNightKey(date: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return false
    val format = nightFormat().apply { isLenient = false }
    return runCatching { format.format(format.parse(date)!!) == date }.getOrDefault(false)
}
