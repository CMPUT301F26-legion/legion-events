/*
 * ReservationDetailsController.kt
 *
 * Purpose: Load one owned reservation for S10b and handle Retry.
 * Role: Controller in MVC; supplies reservation detail state through the repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Read-only information displayed in S10b.
 * @property entry The owned reservation and its optional campsite labels.
 * @property loading Whether the reservation is loading.
 * @property error Safe request failure message, or null.
 */
data class ReservationDetailsState(
    val entry: ReservationEntry? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads one owned reservation per navigation entry and handles Retry.
 * @property reservationId Document ID supplied by the shared router.
 * @property repository Checks ownership and reads the saved booking.
 */
class ReservationDetailsController(
    private val reservationId: String,
    private val repository: ReservationRepository,
) : ViewModel() {
    /** Current detail state, changed only by this controller. */
    private val mutableState = MutableStateFlow(ReservationDetailsState())
    /** Read-only state rendered by S10b. */
    val state = mutableState.asStateFlow()
    /** Current read, cancelled before Retry replaces it. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the reservation without retaining another record's details during a failure. */
    fun retry() {
        request?.cancel()
        mutableState.value = ReservationDetailsState()
        request = viewModelScope.launch {
            try {
                mutableState.value = ReservationDetailsState(repository.getReservation(reservationId), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ReservationDetailsState(loading = false,
                    error = "Could not load this reservation. Check your connection and retry.")
            }
        }
    }
}
