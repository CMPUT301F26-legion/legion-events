/*
 * AdminCampsiteController.kt
 *
 * Purpose: S19 campsite details for an admin: view one campsite and remove it (US 03.01.01).
 * Role: Controller in MVC; AdminCampsiteScreen renders its state and AdminRepository removes the listing.
 * Outstanding issues: Removing the campsite's images (US 03.03.01) belongs to the admin images part.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.ModeratedCampsite
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the admin campsite details show.
 * @property loading Whether the campsite is loading.
 * @property error Why it could not be loaded, or null.
 * @property listing The campsite, or null while loading or when it does not exist.
 * @property ownerName The owner's name.
 * @property cover The cover photo, or null.
 * @property confirmingRemove Whether the remove confirmation is open.
 * @property removing Whether the removal is being saved.
 * @property actionError Why the removal failed, or null.
 */
data class AdminCampsiteState(
    val loading: Boolean = true,
    val error: String? = null,
    val listing: ModeratedCampsite? = null,
    val ownerName: String = "",
    val cover: Photo? = null,
    val confirmingRemove: Boolean = false,
    val removing: Boolean = false,
    val actionError: String? = null,
)

/**
 * Loads one campsite for an admin and removes it after confirmation.
 * @property campsiteId The campsite document ID.
 * @property admin Reads and removes the campsite.
 * @property details Reads the campsite's photos.
 */
class AdminCampsiteController(
    private val campsiteId: String,
    private val admin: AdminRepository,
    private val details: CampsiteDetailsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminCampsiteState())
    /** State collected by the admin campsite details. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads the campsite, its owner's name, and its cover photo. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val listing = admin.getCampsite(campsiteId)
                if (listing == null) {
                    mutableState.value = state.value.copy(loading = false, listing = null,
                        error = "This campsite no longer exists.")
                    return@launch
                }
                val owner = admin.getProfile(listing.campsite.ownerId)?.name
                // A missing photo must not hide the campsite, so photo errors show no cover.
                val cover = try {
                    details.getCampsitePhotos(campsiteId, listing.campsite.photoId).firstOrNull()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                mutableState.value = state.value.copy(loading = false, listing = listing,
                    ownerName = ownerLabel(owner), cover = cover)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load this campsite. Please retry.")
            }
        }
    }

    /** Opens the remove confirmation for an active campsite. */
    fun requestRemove() {
        val current = state.value
        if (current.listing?.active == true && !current.removing) {
            mutableState.value = current.copy(confirmingRemove = true, actionError = null)
        }
    }

    /** Closes the confirmation without removing. */
    fun cancelRemove() {
        if (!state.value.removing) mutableState.value = state.value.copy(confirmingRemove = false)
    }

    /** Removes the campsite, then shows it as removed. */
    fun confirmRemove() {
        val current = state.value
        val listing = current.listing ?: return
        if (current.removing || !current.confirmingRemove) return
        mutableState.value = current.copy(removing = true)
        viewModelScope.launch {
            try {
                admin.removeCampsite(campsiteId)
                mutableState.value = state.value.copy(removing = false, confirmingRemove = false,
                    listing = listing.copy(active = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(removing = false, confirmingRemove = false,
                    actionError = "Could not remove this campsite. Check your connection and retry.")
            }
        }
    }
}
