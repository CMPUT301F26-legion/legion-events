/*
 * AdminImagesController.kt
 *
 * Purpose: S19 Images tab: browse every uploaded image with the campsite and owner it belongs to,
 *   and remove one after confirming (US 03.06.01, 03.03.01).
 * Role: Controller in MVC; AdminImagesScreen renders its state, AdminImageRepository supplies and
 *   deletes images, and AdminRepository supplies campsite and owner names.
 * Outstanding issues: Every image is loaded at once; fine for a course-sized database, but a large
 *   one would need paging.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AdminImageRepository
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One image in the admin grid.
 * @property photo The uploaded image.
 * @property placeName What the image shows: its campsite's name, the profile it belongs to, or a fallback.
 * @property ownerName Who uploaded it.
 * @property cover Whether it is the campsite's cover or the profile's picture.
 */
data class AdminImageRow(val photo: Photo, val placeName: String, val ownerName: String, val cover: Boolean)

/**
 * What the S19 Images tab shows.
 * @property loading Whether the images are loading.
 * @property error Why the images could not be loaded, or null.
 * @property rows Every image, newest first.
 * @property confirming The image waiting for the admin to confirm its removal, or null.
 * @property removing Whether a removal is being saved.
 * @property result Confirmation after a removal, or null.
 * @property actionError Why a removal failed, or null.
 */
data class AdminImagesState(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<AdminImageRow> = emptyList(),
    val confirming: AdminImageRow? = null,
    val removing: Boolean = false,
    val result: String? = null,
    val actionError: String? = null,
)

/**
 * Loads every uploaded image with its campsite and owner names, and removes images.
 * @property images Reads and deletes images.
 * @property admin Reads campsites and profiles to label each image.
 */
class AdminImagesController(
    private val images: AdminImageRepository,
    private val admin: AdminRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminImagesState())
    /** State collected by the S19 Images tab. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads every image. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                coroutineScope {
                    val photos = async { images.getPhotos() }
                    val campsites = async { admin.getCampsites().associateBy { it.campsite.id } }
                    val people = async { admin.getProfiles().associateBy { it.uid } }
                    val sites = campsites.await()
                    val profiles = people.await()
                    val rows = photos.await().map { photo ->
                        val site = sites[photo.targetId]?.takeIf { photo.targetType == "campsite" }?.campsite
                        val person = profiles[photo.targetId]?.takeIf { photo.targetType == "user" }
                        AdminImageRow(
                            photo = photo,
                            placeName = when {
                                site != null -> site.name.ifBlank { "Unnamed campsite" }
                                person != null -> "Profile of ${displayName(person)}"
                                photo.targetType == "user" -> "Unknown profile"
                                else -> "Unknown campsite"
                            },
                            ownerName = ownerLabel(profiles[photo.ownerId]?.name),
                            cover = (site != null && site.photoId == photo.id) || (person != null && person.photoId == photo.id),
                        )
                    }
                    mutableState.value = state.value.copy(loading = false, rows = rows)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load images. Please retry.")
            }
        }
    }

    /**
     * Asks to remove an image (US 03.03.01).
     * @param photoId The image to remove.
     */
    fun requestRemove(photoId: String) {
        val current = state.value
        if (current.removing) return
        val row = current.rows.firstOrNull { it.photo.id == photoId } ?: return
        mutableState.value = current.copy(confirming = row, result = null, actionError = null)
    }

    /** Closes the confirmation without removing. */
    fun cancelRemove() {
        if (!state.value.removing) mutableState.value = state.value.copy(confirming = null)
    }

    /** Deletes the confirmed image and drops it from the grid. */
    fun confirmRemove() {
        val current = state.value
        val pending = current.confirming ?: return
        if (current.removing) return
        mutableState.value = current.copy(removing = true)
        viewModelScope.launch {
            try {
                images.removePhoto(pending.photo.id)
                mutableState.value = state.value.copy(
                    removing = false,
                    confirming = null,
                    rows = state.value.rows.filterNot { it.photo.id == pending.photo.id },
                    result = "Removed an image from ${pending.placeName}.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(removing = false, confirming = null,
                    actionError = "Could not remove the image. Check your connection and retry.")
            }
        }
    }
}
