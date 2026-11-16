/*
 * AdminCampsitesController.kt
 *
 * Purpose: S19 Campsites tab: browse and search every campsite, including removed ones (US 03.04.01).
 * Role: Controller in MVC; AdminCampsitesScreen renders its state and AdminRepository supplies the data.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.ModeratedCampsite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One row of the admin campsite list.
 * @property listing The campsite and whether it is still active.
 * @property ownerName The owner's profile name, or a fallback when it has none.
 */
data class AdminCampsiteRow(val listing: ModeratedCampsite, val ownerName: String)

/**
 * What the S19 Campsites tab shows.
 * @property loading Whether the list is loading.
 * @property error Why the list could not be loaded, or null.
 * @property query The search text.
 * @property rows Every campsite, active ones first, then by name.
 */
data class AdminCampsitesState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val rows: List<AdminCampsiteRow> = emptyList(),
) {
    /** Rows whose name, location, or owner name contain [query], ignoring case. */
    val shown: List<AdminCampsiteRow>
        get() = query.trim().let { text ->
            if (text.isEmpty()) rows else rows.filter { row ->
                listOf(row.listing.campsite.name, row.listing.campsite.locationName, row.ownerName)
                    .any { it.contains(text, ignoreCase = true) }
            }
        }
}

/**
 * Loads every campsite with its owner's name and filters the list by search text.
 * @property admin Reads campsites and profiles.
 */
class AdminCampsitesController(private val admin: AdminRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminCampsitesState())
    /** State collected by the S19 Campsites tab. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads every campsite, keeping the search text. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                coroutineScope {
                    val campsites = async { admin.getCampsites() }
                    val names = async { admin.getProfiles().associate { it.uid to it.name } }
                    val owners = names.await()
                    val rows = campsites.await()
                        .sortedWith(compareBy({ !it.active }, { it.campsite.name.lowercase() }))
                        .map { AdminCampsiteRow(it, ownerLabel(owners[it.campsite.ownerId])) }
                    mutableState.value = state.value.copy(loading = false, rows = rows)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load campsites. Please retry.")
            }
        }
    }

    /**
     * Updates the search text.
     * @param query The search field's current value.
     */
    fun setQuery(query: String) {
        mutableState.value = state.value.copy(query = query)
    }
}

/**
 * Names an owner for admin screens.
 * @param name The owner's saved profile name, or null when the profile is missing.
 * @return The name, or a fallback when it is blank or missing.
 */
internal fun ownerLabel(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "Unnamed owner"
