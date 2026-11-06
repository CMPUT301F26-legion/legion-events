/*
 * OwnerCampsiteController.kt
 *
 * Purpose: Loads one of the owner's campsites, its photo, and its QR code for S14 (US 02.01.04, 02.02.01).
 * Role: Controller in MVC; OwnerCampsiteScreen renders its state and OwnerCampsiteRepository supplies the data.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.CampsiteQr
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the owner campsite details screen (S14) displays.
 * @property loading Whether the first load is still running.
 * @property error Why the campsite could not be loaded, or null.
 * @property campsite The listing, or null before loading or when it is unavailable.
 * @property badge Its lottery state, or null when lotteries could not be loaded.
 * @property cover The cover photo, or null when there is none or it failed to load.
 * @property qrText The QR code's text once the owner taps Generate QR, otherwise null.
 */
data class OwnerCampsiteState(
    val loading: Boolean = true,
    val error: String? = null,
    val campsite: Campsite? = null,
    val badge: LotteryBadge? = null,
    val cover: Photo? = null,
    val qrText: String? = null,
)

/**
 * Loads one of the owner's campsites for S14 and generates its QR code text.
 * The screen calls [refresh] each time it resumes, so edits from S13 appear on return.
 * @property campsiteId The campsite document ID from the route.
 * @property repository Reads the owner's campsites, lotteries, and photos.
 * @property now Supplies the current time, replaceable in tests.
 */
class OwnerCampsiteController(
    private val campsiteId: String,
    private val repository: OwnerCampsiteRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(OwnerCampsiteState())
    /** Read-only state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before the next one starts. */
    private var request: Job? = null

    /** Reloads the campsite, keeping what is shown while the request runs. */
    fun refresh() {
        request?.cancel()
        if (state.value.error != null) mutableState.value = OwnerCampsiteState()
        request = viewModelScope.launch {
            try {
                val lotteries = async { runCatching { repository.getMyLotteries() } }
                val site = repository.getMyCampsite(campsiteId)
                if (site == null) {
                    mutableState.value = OwnerCampsiteState(loading = false)
                    return@launch
                }
                val loaded = lotteries.await().getOrElse { if (it is CancellationException) throw it else null }
                val previous = state.value
                mutableState.value = previous.copy(
                    loading = false,
                    error = null,
                    campsite = site,
                    badge = loaded?.let { lotteryBadge(site.id, it, now()) },
                    cover = previous.cover?.takeIf { it.id == site.photoId },
                )
                val photoId = site.photoId
                if (photoId != null && state.value.cover == null) {
                    val cover = runCatching { repository.getPhotos(listOf(photoId)).firstOrNull() }
                        .getOrElse { if (it is CancellationException) throw it else null }
                    mutableState.value = state.value.copy(cover = cover)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = OwnerCampsiteState(loading = false,
                    error = "Could not load this campsite. Check your connection and retry.")
            }
        }
    }

    /** Creates the QR code text for this campsite. The code holds only its ID (see [CampsiteQr]). */
    fun generateQr() {
        val site = state.value.campsite ?: return
        mutableState.value = state.value.copy(qrText = CampsiteQr.encode(site.id))
    }
}
