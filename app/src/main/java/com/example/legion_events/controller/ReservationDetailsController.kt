/*
 * ReservationDetailsController.kt
 *
 * Purpose: Coordinate owned booking details, cancellation requests, and receipts on S09/S10b.
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
import com.example.legion_events.model.cancellationCsv
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * @property confirmingCancellation Whether the user is reviewing the cancellation dialog.
 * @property requestingCancellation Whether a request is being committed.
 * @property cancellationNotice Request or status-check failure, or null.
 */
data class ReservationDetailsState(
    val entry: ReservationEntry? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportNotice: String? = null,
    val confirmingCancellation: Boolean = false,
    val requestingCancellation: Boolean = false,
    val cancellationNotice: String? = null,
)

/**
 * Loads one owned reservation per navigation entry and handles Retry.
 * @property reservationId Document ID supplied by the shared router.
 * @property repository Checks ownership and reads the saved booking.
 * @property now Clock for cancellation eligibility; checkout ends new requests.
 */
class ReservationDetailsController(
    private val reservationId: String,
    private val repository: ReservationRepository,
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    /** Current detail state, changed only by this controller. */
    private val mutableState = MutableStateFlow(ReservationDetailsState())
    /** Read-only state rendered by the confirmation or details screen. */
    val state = mutableState.asStateFlow()
    /** Current read, cancelled before Retry replaces it. */
    private var request: Job? = null
    /** Status checks run only while the screen is resumed and a cancellation is pending. */
    private var tracking: Job? = null
    private var resumed = false

    init { retry() }

    /** Reloads the reservation without retaining another record's details during a failure. */
    fun retry() {
        if (state.value.exporting || state.value.requestingCancellation) return
        request?.cancel()
        tracking?.cancel()
        mutableState.value = ReservationDetailsState()
        request = viewModelScope.launch {
            try {
                mutableState.value = ReservationDetailsState(repository.getReservation(reservationId), loading = false)
                trackCancellation()
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
        exportReceipt(false, write)
    }

    /** Exports a cancellation receipt only after a fresh read confirms worker completion. */
    fun exportCancellation(write: suspend (String) -> Unit) {
        exportReceipt(true, write)
    }

    /** Reuses save handling while keeping booking and cancellation receipts distinct across a status change. */
    private fun exportReceipt(cancellation: Boolean, write: suspend (String) -> Unit) {
        val current = state.value
        val expectedStatus = if (cancellation) Reservation.STATUS_CANCELLED else Reservation.STATUS_CONFIRMED
        if (current.loading || current.exporting || current.requestingCancellation ||
            current.entry?.reservation?.status != expectedStatus) return
        mutableState.value = current.copy(exporting = true, exportNotice = null)
        viewModelScope.launch {
            try {
                val entry = repository.getReservation(reservationId)
                when {
                    entry == null -> mutableState.value = ReservationDetailsState(loading = false)
                    entry.reservation.status != expectedStatus -> {
                        mutableState.value = ReservationDetailsState(entry, loading = false,
                            exportNotice = "The reservation status changed. Please choose its current receipt.")
                    }
                    else -> {
                        val csv = if (cancellation) entry.reservation.cancellationCsv(entry.campsiteName, entry.locationName)
                            else entry.reservation.confirmationCsv(entry.campsiteName, entry.locationName)
                        write(csv)
                        mutableState.value = state.value.copy(entry = entry, exporting = false,
                            exportNotice = if (cancellation) "Cancellation CSV saved." else "Confirmation CSV saved.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(exporting = false,
                    exportNotice = if (cancellation) "Could not save the cancellation CSV. Try downloading again."
                        else "Could not save the confirmation CSV. Try downloading again.")
            }
        }
    }

    /** Opens confirmation only for a cancellable stay, never submits the request itself. */
    fun requestCancel() {
        if (!state.value.loading && !state.value.exporting && !state.value.requestingCancellation &&
            state.value.entry?.reservation?.canRequestCancellation(now()) == true) {
            mutableState.value = state.value.copy(confirmingCancellation = true, cancellationNotice = null)
        }
    }

    /** Closes the dialog without changing the saved reservation. */
    fun dismissCancellation() {
        mutableState.value = state.value.copy(confirmingCancellation = false)
    }

    /** Submits the confirmed request once; an uncertain result can be reloaded and safely retried. */
    fun confirmCancellation() {
        if (!state.value.confirmingCancellation || state.value.requestingCancellation || state.value.exporting) return
        mutableState.value = state.value.copy(confirmingCancellation = false, requestingCancellation = true)
        viewModelScope.launch {
            try {
                val entry = repository.requestCancellation(reservationId)
                mutableState.value = state.value.copy(entry = entry, requestingCancellation = false, exportNotice = null)
                trackCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(requestingCancellation = false,
                    cancellationNotice = "Could not confirm your cancellation request. Refresh to check, then try again.")
            }
        }
    }

    /** Resumes pending status checks without submitting a request or repeating the initial read. */
    fun resume() { resumed = true; trackCancellation() }

    /** Stops status checks while the screen is hidden or the app is in the background. */
    fun pause() { resumed = false; tracking?.cancel() }

    /** Checks pending requests every five seconds; only a worker-written terminal state confirms cancellation. */
    private fun trackCancellation() {
        tracking?.cancel()
        if (!resumed || state.value.entry?.reservation?.cancellationRequested != true) return
        tracking = viewModelScope.launch {
            while (state.value.entry?.reservation?.let { it.status == Reservation.STATUS_CONFIRMED && it.cancellationRequested } == true) {
                delay(5_000)
                if (state.value.exporting || state.value.requestingCancellation) continue
                try {
                    val entry = repository.getReservation(reservationId)
                    if (state.value.exporting || state.value.requestingCancellation) continue
                    mutableState.value = state.value.copy(entry = entry, cancellationNotice = null, exportNotice = null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = state.value.copy(cancellationNotice = "Could not check cancellation status. Refresh to retry.")
                    break
                }
            }
        }
    }

    /** Explains how to recover when the device has no app that can open the Save dialog. */
    fun reportSaveUnavailable() {
        mutableState.value = state.value.copy(exportNotice = "No app can save documents on this device.")
    }
}
