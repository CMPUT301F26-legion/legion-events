package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Accepts addresses such as `name@example.com`; the exact format is not verified further. */
private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
/** Accepts digits with optional `+`, spaces, dashes, dots, and brackets. */
private val PHONE = Regex("""^\+?[\d\s().-]{7,20}$""")

/**
 * What the profile screen displays.
 * @property loading Whether the saved profile is still loading.
 * @property loadError Why the profile could not be loaded, or null.
 * @property name The name field's current text.
 * @property email The email field's current text.
 * @property phone The phone field's current text.
 * @property nameError Why the name cannot be saved, or null.
 * @property emailError Why the email cannot be saved, or null.
 * @property phoneError Why the phone number cannot be saved, or null.
 * @property notificationsEnabled Whether the user receives notifications.
 * @property saving Whether a contact or notification change is being saved.
 * @property message A short result to show after saving, or null.
 * @property confirmingDelete Whether the delete confirmation dialog is open.
 * @property deleting Whether the profile is being deleted.
 * @property deleted Whether deletion finished, so the app should open a new account.
 */
data class ProfileState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val nameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val notificationsEnabled: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
)

/**
 * Loads the user's profile and handles edits, the notification opt-out, and deletion.
 * @property repository Reads and writes the signed-in user's profile.
 */
class ProfileController(private val repository: ProfileRepository) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(ProfileState())
    /** Read-only state collected by the profile screen. */
    val state = mutableState.asStateFlow()
    /** Current load request, cancelled before retrying. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the saved profile, discarding unsaved edits. */
    fun retry() {
        request?.cancel()
        mutableState.value = ProfileState()
        request = viewModelScope.launch {
            mutableState.value = try {
                val profile = repository.getProfile()
                ProfileState(loading = false, name = profile.name, email = profile.email,
                    phone = profile.phone, notificationsEnabled = profile.notificationsEnabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProfileState(loading = false, loadError = "Could not load your profile. Check your connection and retry.")
            }
        }
    }

    /** Updates the name field and clears its error. */
    fun setName(name: String) { mutableState.value = state.value.copy(name = name, nameError = null, message = null) }

    /** Updates the email field and clears its error. */
    fun setEmail(email: String) { mutableState.value = state.value.copy(email = email, emailError = null, message = null) }

    /** Updates the phone field and clears its error. */
    fun setPhone(phone: String) { mutableState.value = state.value.copy(phone = phone, phoneError = null, message = null) }

    /** Checks the fields and saves them when valid. Email and phone are optional. */
    fun save() {
        val current = state.value
        if (current.loading || current.saving) return
        val name = current.name.trim()
        val email = current.email.trim()
        val phone = current.phone.trim()
        val checked = current.copy(
            name = name, email = email, phone = phone,
            nameError = if (name.isEmpty()) "Enter your name." else null,
            emailError = if (email.isNotEmpty() && !EMAIL.matches(email)) "Enter a valid email address." else null,
            phoneError = if (phone.isNotEmpty() && !PHONE.matches(phone)) "Enter a valid phone number." else null,
            message = null,
        )
        if (checked.nameError != null || checked.emailError != null || checked.phoneError != null) {
            mutableState.value = checked
            return
        }
        mutableState.value = checked.copy(saving = true)
        viewModelScope.launch {
            val message = try {
                repository.updateContact(name, email, phone)
                "Profile saved."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Could not save your profile. Check your connection and retry."
            }
            mutableState.value = state.value.copy(saving = false, message = message)
        }
    }

    /**
     * Saves the notification choice immediately, restoring the old value if saving fails.
     * @param enabled False opts out of notifications.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        val current = state.value
        if (current.loading || current.saving || enabled == current.notificationsEnabled) return
        mutableState.value = current.copy(notificationsEnabled = enabled, saving = true, message = null)
        viewModelScope.launch {
            mutableState.value = try {
                repository.setNotificationsEnabled(enabled)
                state.value.copy(saving = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value.copy(notificationsEnabled = !enabled, saving = false,
                    message = "Could not update notifications. Check your connection and retry.")
            }
        }
    }

    /** Opens the delete confirmation dialog. */
    fun requestDelete() {
        if (!state.value.loading && !state.value.deleting) mutableState.value = state.value.copy(confirmingDelete = true)
    }

    /** Closes the delete confirmation dialog without deleting anything. */
    fun cancelDelete() { mutableState.value = state.value.copy(confirmingDelete = false) }

    /** Deletes the profile after the user confirms. */
    fun confirmDelete() {
        if (state.value.deleting) return
        mutableState.value = state.value.copy(confirmingDelete = false, deleting = true, message = null)
        viewModelScope.launch {
            mutableState.value = try {
                repository.deleteProfile()
                state.value.copy(deleting = false, deleted = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value.copy(deleting = false,
                    message = "Could not delete your profile. Check your connection and retry.")
            }
        }
    }
}
