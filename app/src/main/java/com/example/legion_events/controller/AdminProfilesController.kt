/*
 * AdminProfilesController.kt
 *
 * Purpose: S19 Profiles tab: browse and search every profile, remove a profile, or remove an owner
 *   who broke the app policy together with their campsites (US 03.05.01, 03.02.01, 03.07.01).
 * Role: Controller in MVC; AdminProfilesScreen renders its state and AdminRepository saves removals.
 * Outstanding issues: Admin accounts cannot be removed here; roles are managed in the Firebase console.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One row of the admin profile list.
 * @property profile The user's profile.
 * @property activeCampsites How many active campsites the user owns; above zero means an owner.
 */
data class AdminProfileRow(val profile: UserProfile, val activeCampsites: Int) {
    /** Whether the account is an admin, which this screen never removes. */
    val admin: Boolean get() = profile.role == "admin"

    /** The label shown on the row: Admin, Owner, or Entrant. */
    val roleLabel: String get() = when {
        admin -> "Admin"
        activeCampsites > 0 -> "Owner"
        else -> "Entrant"
    }
}

/**
 * A removal waiting for the admin to confirm.
 * @property userId The profile to remove.
 * @property name The profile's display name, for the dialog.
 * @property withCampsites True to also remove the owner's campsites (US 03.07.01).
 */
data class PendingRemoval(val userId: String, val name: String, val withCampsites: Boolean)

/**
 * What the S19 Profiles tab shows.
 * @property loading Whether the list is loading.
 * @property error Why the list could not be loaded, or null.
 * @property query The search text.
 * @property rows Every profile, active ones first, then by name.
 * @property confirming The removal waiting for confirmation, or null.
 * @property removing Whether a removal is being saved.
 * @property result Confirmation after a removal, or null.
 * @property actionError Why a removal failed, or null.
 */
data class AdminProfilesState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val rows: List<AdminProfileRow> = emptyList(),
    val confirming: PendingRemoval? = null,
    val removing: Boolean = false,
    val result: String? = null,
    val actionError: String? = null,
) {
    /** Rows whose name, email, or phone contain [query], ignoring case. */
    val shown: List<AdminProfileRow>
        get() = query.trim().let { text ->
            if (text.isEmpty()) rows else rows.filter { row ->
                listOf(row.profile.name, row.profile.email, row.profile.phone).any { it.contains(text, ignoreCase = true) }
            }
        }
}

/**
 * Loads every profile, counts each user's active campsites, and removes profiles or owners.
 * @property admin Reads and moderates profiles and campsites.
 */
class AdminProfilesController(private val admin: AdminRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminProfilesState())
    /** State collected by the S19 Profiles tab. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads every profile, keeping the search text. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                coroutineScope {
                    val profiles = async { admin.getProfiles() }
                    val owned = async {
                        admin.getCampsites().filter { it.active }.groupingBy { it.campsite.ownerId }.eachCount()
                    }
                    val counts = owned.await()
                    val rows = profiles.await()
                        .sortedWith(compareBy({ it.disabled }, { displayName(it).lowercase() }))
                        .map { AdminProfileRow(it, counts[it.uid] ?: 0) }
                    mutableState.value = state.value.copy(loading = false, rows = rows)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load profiles. Please retry.")
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

    /**
     * Asks to remove a profile (US 03.02.01).
     * @param userId The profile to remove.
     */
    fun requestRemoveProfile(userId: String) = askToRemove(userId, withCampsites = false)

    /**
     * Asks to remove an owner and all of their campsites (US 03.07.01).
     * @param userId The owner to remove.
     */
    fun requestRemoveOwner(userId: String) = askToRemove(userId, withCampsites = true)

    /** Closes the confirmation without removing. */
    fun cancelRemove() {
        if (!state.value.removing) mutableState.value = state.value.copy(confirming = null)
    }

    /** Saves the confirmed removal, then reloads the list. */
    fun confirmRemove() {
        val current = state.value
        val pending = current.confirming ?: return
        if (current.removing) return
        mutableState.value = current.copy(removing = true)
        viewModelScope.launch {
            try {
                val message = if (pending.withCampsites) {
                    val count = admin.removeOwner(pending.userId)
                    "Removed ${pending.name} and $count ${if (count == 1) "campsite" else "campsites"}."
                } else {
                    admin.removeProfile(pending.userId)
                    "Removed ${pending.name}."
                }
                mutableState.value = state.value.copy(removing = false, confirming = null, result = message)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(removing = false, confirming = null,
                    actionError = "Could not remove ${pending.name}. Check your connection and retry.")
            }
        }
    }

    /** Opens a confirmation for an active, non-admin profile. */
    private fun askToRemove(userId: String, withCampsites: Boolean) {
        val current = state.value
        val row = current.rows.firstOrNull { it.profile.uid == userId } ?: return
        if (current.removing || row.admin || row.profile.disabled) return
        mutableState.value = current.copy(confirming = PendingRemoval(userId, displayName(row.profile), withCampsites),
            result = null, actionError = null)
    }
}

/**
 * Names a profile for admin screens.
 * @param profile The profile.
 * @return Its name, or a fallback when it has none.
 */
internal fun displayName(profile: UserProfile): String = profile.name.ifBlank { "Unnamed user" }
