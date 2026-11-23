/*
 * RateStayController.kt
 *
 * Purpose: Load a completed stay and coordinate rating selection and saving on S10c.
 * Role: Controller in MVC; owns lifecycle state, draft restoration, and user actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.RatingDetails
import com.example.legion_events.data.RatingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S10c's saved stay, score selection, and request state.
 * @property details Eligible stay and its existing campsite rating; null when unavailable.
 * @property score Selected score, or null until the entrant chooses one.
 * @property loading Whether the initial read or Retry is pending.
 * @property error Initial read failure message, or null.
 * @property saving Whether a submit is pending.
 * @property saveError Submit failure message; the selected score remains available for retry.
 * @property saved Whether an explicit submit succeeded and the router may return to Bookings.
 */
data class RateStayState(
    val details: RatingDetails? = null,
    val score: Int? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
)

/**
 * Loads once per navigation entry and never submits automatically after recreation.
 * @property reservationId Saved booking ID supplied by AppNavigation.
 * @property repository Checks ownership and completed-stay eligibility on load and save.
 * @property savedState Keeps an unsaved score across screen and process recreation.
 */
class RateStayController(
    private val reservationId: String,
    private val repository: RatingRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RateStayState())
    /** Read-only state rendered by S10c. */
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    init { retry() }

    /** Reloads eligibility and the existing score, preserving a valid unsaved selection. */
    fun retry() {
        if (state.value.saving || state.value.saved) return
        request?.cancel()
        mutableState.value = RateStayState()
        request = viewModelScope.launch {
            try {
                val details = repository.getForStay(reservationId)
                val draft = savedState.get<Int>("ratingScore")?.takeIf { it in 1..5 }
                mutableState.value = RateStayState(details, draft ?: details?.rating?.score, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RateStayState(loading = false, error = "Could not load this stay's rating. Check your connection and retry.")
            }
        }
    }

    /** Selects one 1–5 score without writing anything to Firebase. */
    fun selectScore(score: Int) {
        if (score !in 1..5 || state.value.loading || state.value.details == null || state.value.saving || state.value.saved) return
        savedState["ratingScore"] = score
        mutableState.value = state.value.copy(score = score, saveError = null)
    }

    /** Saves only an explicit valid selection and prevents duplicate submissions while saving. */
    fun submit() {
        val current = state.value
        val score = current.score ?: return
        if (current.loading || current.details == null || current.saving || current.saved) return
        mutableState.value = current.copy(saving = true, saveError = null)
        request = viewModelScope.launch {
            try {
                val saved = repository.saveForStay(reservationId, score)
                mutableState.value = state.value.copy(score = saved.score, saving = false, saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, saveError = "Could not save your rating. Check your connection or access, then retry.")
            }
        }
    }
}
