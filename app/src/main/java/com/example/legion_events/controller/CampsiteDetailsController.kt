package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Read-only information shown by the details screen.
 * @property campsite Selected listing, or null before loading or when unavailable.
 * @property loading Whether the campsite request is pending.
 * @property error Campsite request failure message.
 * @property photos Photos belonging to this campsite, cover first.
 * @property photosLoading Whether photos are still loading.
 * @property photosError Photo request failure message; listing information remains visible.
 */
data class CampsiteDetailsState(
    val campsite: Campsite? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val photos: List<Photo> = emptyList(),
    val photosLoading: Boolean = false,
    val photosError: String? = null,
)

/**
 * Loads a selected campsite once per navigation entry and handles explicit retries.
 * @property campsiteId Document ID passed through the shared router.
 * @property repository Provides campsite and photo reads.
 */
class CampsiteDetailsController(
    private val campsiteId: String,
    private val repository: CampsiteDetailsRepository,
) : ViewModel() {
    /** Current details and photo request state, changed only by this controller. */
    private val mutableState = MutableStateFlow(CampsiteDetailsState())
    /** State collected by the view with lifecycle awareness. */
    val state = mutableState.asStateFlow()
    /** Current listing request, cancelled before retrying. */
    private var request: Job? = null
    /** Independent photo request so an image failure does not hide the listing. */
    private var photoRequest: Job? = null

    init { retry() }

    /** Reloads the campsite, cancelling previous work instead of creating duplicate requests. */
    fun retry() {
        request?.cancel()
        photoRequest?.cancel()
        mutableState.value = CampsiteDetailsState()
        request = viewModelScope.launch {
            try {
                val campsite = repository.getCampsite(campsiteId)
                mutableState.value = CampsiteDetailsState(campsite = campsite, loading = false)
                if (campsite != null) retryPhotos()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = CampsiteDetailsState(loading = false,
                    error = "Could not load this campsite. Check your connection and retry.")
            }
        }
    }

    /** Retries photos without reloading the campsite or losing its visible information. */
    fun retryPhotos() {
        val campsite = state.value.campsite ?: return
        photoRequest?.cancel()
        mutableState.value = state.value.copy(photosLoading = true, photosError = null)
        photoRequest = viewModelScope.launch {
            try {
                val photos = repository.getCampsitePhotos(campsite.id, campsite.photoId)
                mutableState.value = state.value.copy(photos = photos, photosLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(photosLoading = false,
                    photosError = "Could not load campsite photos. Please retry.")
            }
        }
    }
}
