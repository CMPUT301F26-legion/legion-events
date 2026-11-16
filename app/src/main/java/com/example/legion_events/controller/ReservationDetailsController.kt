/*
 * ReservationDetailsController.kt
 *
 * Purpose: Load an owned reservation for S09/S10b and export its confirmation CSV.
 * Role: Controller in MVC; supplies reservation detail state through the repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.confirmationCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Read-only booking information displayed in S09 and S10b.
 * @property entry The owned reservation and its optional campsite labels.
 * @property loading Whether the reservation is loading.
 * @property error Safe request failure message, or null.
 * @property exporting Whether a confirmation is being rechecked or saved.
 * @property exportNotice Save result or recovery message, or null.
 */
data class ReservationDetailsState(
    val entry: ReservationEntry? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportNotice: String? = null,
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
    /** Read-only state rendered by the confirmation or details screen. */
    val state = mutableState.asStateFlow()
    /** Current read, cancelled before Retry replaces it. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the reservation without retaining another record's details during a failure. */
    fun retry() {
        if (state.value.exporting) return
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

    /**
     * Rechecks ownership and current status before saving a confirmation exactly once per action.
     * @param write Saves the CSV to the user-selected document; failures leave Download available to retry.
     */
    fun exportConfirmation(write: suspend (String) -> Unit) {
        val current = state.value
        if (current.loading || current.exporting || current.entry?.reservation?.status != Reservation.STATUS_CONFIRMED) return
        mutableState.value = current.copy(exporting = true, exportNotice = null)
        viewModelScope.launch {
            try {
                val entry = repository.getReservation(reservationId)
                when {
                    entry == null -> mutableState.value = ReservationDetailsState(loading = false)
                    entry.reservation.status != Reservation.STATUS_CONFIRMED -> {
                        mutableState.value = ReservationDetailsState(entry, loading = false,
                            exportNotice = "Confirmation is only available for confirmed reservations.")
                    }
                    else -> {
                        write(entry.reservation.confirmationCsv(entry.campsiteName, entry.locationName))
                        mutableState.value = state.value.copy(entry = entry, exporting = false,
                            exportNotice = "Confirmation CSV saved.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(exporting = false,
                    exportNotice = "Could not save the confirmation CSV. Try downloading again.")
            }
        }
    }

    /** Explains how to recover when the device has no app that can open the Save dialog. */
    fun reportSaveUnavailable() {
        mutableState.value = state.value.copy(exportNotice = "No app can save documents on this device.")
    }
}
