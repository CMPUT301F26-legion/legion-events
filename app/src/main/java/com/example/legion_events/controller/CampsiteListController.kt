package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
