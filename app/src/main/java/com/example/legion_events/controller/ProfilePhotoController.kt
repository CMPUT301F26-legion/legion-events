/*
 * ProfilePhotoController.kt
 *
 * Purpose: Add, replace, and remove the entrant's profile image (US 01.08.02).
 * Role: Focused S11 controller in MVC; coordinates encoding and repository actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Image state displayed independently of unsaved contact edits.
 * @property photo Current saved profile image, or null.
 * @property working Whether loading, encoding, or saving is in progress.
 * @property error A recoverable image error, or null.
 * @property mutating Whether encoding or an image write is pending; passive reads do not block contact actions.
 */
data class ProfilePhotoState(val photo: Photo? = null, val working: Boolean = true, val error: String? = null, val mutating: Boolean = false)

/**
 * Keeps image changes separate from contact drafts and never retries a write automatically.
 * @property repository Reads and edits only the authenticated user's profile image.
 * @property imageDispatcher Runs image decoding/compression off the main thread; tests inject their scheduler.
 */
class ProfilePhotoController(private val repository: ProfileRepository,
    private val imageDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(ProfilePhotoState())
    /** Read-only image state rendered on S11. */
    val state = mutableState.asStateFlow()
    private var retryAction: (() -> Unit)? = null

    init { load() }

    /** Loads the current image; failure does not discard contact fields or a previous image. */
    fun load() {
        retryAction = null
        perform(mutating = false) { mutableState.value = state.value.copy(photo = repository.getPhoto()) }
    }

    /** Encodes a picked image off the main thread, then saves it with one stable operation ID. */
    fun changePhoto(read: suspend () -> String?) {
        if (state.value.working) return
        val previous = state.value.photo?.id
        retryAction = { changePhoto(read) }
        perform {
            val bytes = checkNotNull(withContext(imageDispatcher) { read() }) { "Unreadable image." }
            val id = UUID.randomUUID().toString()
            retryAction = { savePrepared(id, bytes, previous) }
            mutableState.value = state.value.copy(photo = repository.setPhoto(id, bytes, previous))
        }
    }

    /** Reuses encoded data and the operation ID after an uncertain save result. */
    private fun savePrepared(id: String, bytes: String, previous: String?) { perform { mutableState.value = state.value.copy(photo = repository.setPhoto(id, bytes, previous)) } }

    /** Removes the displayed image, preserving its ID for a safe explicit retry. */
    fun removePhoto() {
        if (state.value.working) return
        val id = state.value.photo?.id ?: return
        retryAction = { removeExpected(id) }
        removeExpected(id)
    }

    /** Clears only the expected image so a retry cannot delete a newer replacement. */
    private fun removeExpected(id: String) { perform { repository.removePhoto(id); mutableState.value = state.value.copy(photo = null) } }

    /** Retries the failed action explicitly, or reloads when there is no pending change. */
    fun retry() { if (!state.value.working) retryAction?.invoke() ?: load() }

    /** Reports a missing system picker without changing the current image. */
    fun pickerUnavailable() { mutableState.value = state.value.copy(error = "No image picker is available on this device.") }

    /** Serializes image actions and keeps the previous image visible until a write succeeds. */
    private fun perform(mutating: Boolean = true, action: suspend () -> Unit) {
        mutableState.value = state.value.copy(working = true, mutating = mutating, error = null)
        viewModelScope.launch {
            try { action(); retryAction = null; mutableState.value = state.value.copy(working = false, mutating = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(working = false, mutating = false, error = "Could not update your profile image. Try a readable image or Retry.") }
        }
    }
}
