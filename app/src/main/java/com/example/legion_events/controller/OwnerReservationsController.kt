/*
 * OwnerReservationsController.kt
 *
 * Purpose: S17 owner reservations: pick a campsite, see its booked nights, list active, past,
 *   and cancelled bookings, and reject one (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: Controller in MVC; OwnerReservationsScreen renders its state and
 *   OwnerReservationRepository reads and rejects bookings.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.OwnerReservationRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.monthOf
import com.example.legion_events.model.shiftMonth
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The three booking lists on S17. */
enum class OwnerReservationTab { ACTIVE, PAST, CANCELLED }

/**
 * The booking the owner is about to reject, shown in S17's confirmation dialog.
 * @property reservation The booking.
 * @property reason The optional reason being written.
 * @property inProgress Whether the rejection is being saved.
 * @property error Why the rejection failed, or null.
 */
data class RejectionDraft(
    val reservation: Reservation,
    val reason: String = "",
    val inProgress: Boolean = false,
    val error: String? = null,
)

/**
 * What S17 shows.
 * @property loading Whether campsites or bookings are loading.
 * @property error Why they could not be loaded, or null.
 * @property campsites The owner's campsites for the dropdown, sorted by name.
 * @property selectedCampsiteId The campsite whose bookings are shown, or null when the owner has none.
 * @property active Upcoming and ongoing requested and confirmed bookings, by check-in.
 * @property past Finished confirmed bookings, latest checkout first.
 * @property cancelled Cancelled and rejected bookings, most recently changed first.
 * @property bookedNights `YYYY-MM-DD` nights held by confirmed bookings, for the calendar.
 * @property requestedNights `YYYY-MM-DD` nights asked for by requested bookings that are not also
 *   in [bookedNights], shown differently on the calendar.
 * @property month The `YYYY-MM` month shown in the calendar.
 * @property selectedTab Which booking list is shown.
 * @property viewing The booking whose details dialog is open, or null.
 * @property rejecting The rejection dialog's state, or null when it is closed.
 * @property result Confirmation after rejecting, or null.
 */
data class OwnerReservationsState(
    val loading: Boolean = true,
    val error: String? = null,
    val campsites: List<Campsite> = emptyList(),
    val selectedCampsiteId: String? = null,
    val active: List<Reservation> = emptyList(),
    val past: List<Reservation> = emptyList(),
    val cancelled: List<Reservation> = emptyList(),
    val bookedNights: Set<String> = emptySet(),
    val requestedNights: Set<String> = emptySet(),
    val month: String = "",
    val selectedTab: OwnerReservationTab = OwnerReservationTab.ACTIVE,
    val viewing: Reservation? = null,
    val rejecting: RejectionDraft? = null,
    val result: String? = null,
) {
    /**
     * The bookings in the selected tab.
     * @return The list the screen shows below the tabs.
     */
    fun visibleEntries(): List<Reservation> = when (selectedTab) {
        OwnerReservationTab.ACTIVE -> active
        OwnerReservationTab.PAST -> past
        OwnerReservationTab.CANCELLED -> cancelled
    }
}

/**
 * Loads the owner's campsites and the selected campsite's bookings, and rejects bookings.
 * @property loadCampsites Reads the owner's campsites.
 * @property repository Reads and rejects bookings at those campsites.
 * @property savedState Keeps the campsite, tab, and month when the screen or process is recreated.
 * @property now Clock that splits active from past bookings; tests supply a fixed clock.
 */
class OwnerReservationsController(
    private val loadCampsites: suspend () -> List<Campsite>,
    private val repository: OwnerReservationRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(OwnerReservationsState(
        selectedCampsiteId = savedState[CAMPSITE_KEY],
        month = savedState[MONTH_KEY] ?: "",
        selectedTab = OwnerReservationTab.entries.firstOrNull { it.name == savedState.get<String>(TAB_KEY) }
            ?: OwnerReservationTab.ACTIVE,
    ))
    /** State collected by S17. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before another load starts. */
    private var request: Job? = null

    /** Reloads the campsites and the selected campsite's bookings, keeping the campsite, tab, and month. */
    fun refresh() = load {
        // A refresh (including on resume) starts fresh, so an old confirmation does not linger.
        mutableState.value = state.value.copy(result = null)
        val campsites = loadCampsites().sortedBy { it.name.lowercase() }
        val selected = state.value.selectedCampsiteId?.takeIf { id -> campsites.any { it.id == id } }
            ?: campsites.firstOrNull()?.id
        if (selected != state.value.selectedCampsiteId) savedState.remove<String>(MONTH_KEY)
        mutableState.value = state.value.copy(campsites = campsites,
            month = if (selected == state.value.selectedCampsiteId) state.value.month else "")
        choose(selected)
        loadBookings(selected)
    }

    /**
     * Shows another campsite's bookings and calendar.
     * @param campsiteId One of [OwnerReservationsState.campsites].
     */
    fun selectCampsite(campsiteId: String) {
        if (campsiteId == state.value.selectedCampsiteId || state.value.campsites.none { it.id == campsiteId }) return
        savedState.remove<String>(MONTH_KEY)
        choose(campsiteId)
        mutableState.value = state.value.copy(month = "", active = emptyList(), past = emptyList(),
            cancelled = emptyList(), bookedNights = emptySet(), requestedNights = emptySet(), result = null)
        load { loadBookings(campsiteId) }
    }

    /**
     * Shows another booking list without reading the database again.
     * @param tab The list to show.
     */
    fun selectTab(tab: OwnerReservationTab) {
        savedState[TAB_KEY] = tab.name
        mutableState.value = state.value.copy(selectedTab = tab)
    }

    /**
     * Moves the calendar forward or back.
     * @param by Months to move; negative moves back.
     */
    fun showMonth(by: Int) {
        val month = shiftMonth(state.value.month.ifEmpty { monthOf(now()) }, by)
        savedState[MONTH_KEY] = month
        mutableState.value = state.value.copy(month = month)
    }

    /**
     * Opens a booking's details (reservation number, dates, guests, total, rules accepted).
     * Entrant profiles are private, so the entrant is not named.
     * @param reservation The booking to show.
     */
    fun viewBooking(reservation: Reservation) {
        mutableState.value = state.value.copy(viewing = reservation)
    }

    /** Closes the booking details. */
    fun closeBooking() {
        mutableState.value = state.value.copy(viewing = null)
    }

    /**
     * Opens the rejection dialog for an upcoming requested or confirmed booking; other bookings
     * cannot be rejected.
     * @param reservation The booking to reject.
     */
    fun requestReject(reservation: Reservation) {
        if (!reservation.isUpcomingForOwner(now())) return
        mutableState.value = state.value.copy(viewing = null, rejecting = RejectionDraft(reservation), result = null)
    }

    /**
     * Updates the optional rejection reason, cut to the longest reason the notice allows.
     * @param reason The text field's current value.
     */
    fun setRejectReason(reason: String) {
        val draft = state.value.rejecting ?: return
        if (draft.inProgress) return
        mutableState.value = state.value.copy(rejecting = draft.copy(
            reason = reason.take(Reservation.MAX_REJECTION_REASON_LENGTH), error = null))
    }

    /** Closes the rejection dialog unless the rejection is being saved. */
    fun cancelReject() {
        if (state.value.rejecting?.inProgress == true) return
        mutableState.value = state.value.copy(rejecting = null)
    }

    /** Rejects the booking, notifies the entrant, and reloads so it moves to the Cancelled tab. */
    fun confirmReject() {
        val draft = state.value.rejecting ?: return
        if (draft.inProgress) return
        mutableState.value = state.value.copy(rejecting = draft.copy(inProgress = true, error = null))
        viewModelScope.launch {
            try {
                repository.rejectReservation(draft.reservation.id, draft.reason.trim().ifEmpty { null })
                mutableState.value = state.value.copy(rejecting = null,
                    result = "Reservation rejected. The entrant was notified.")
                load { loadBookings(state.value.selectedCampsiteId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                rejectFailed(draft, error)
            } catch (error: SecurityException) {
                // "Not your campsite" and "account changed" are reasons, not connection problems.
                rejectFailed(draft, error)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(rejecting = draft.copy(inProgress = false, error = REJECT_FAILED))
            }
        }
    }

    /**
     * Shows why a rejection failed in its dialog.
     * @param draft The rejection that failed.
     * @param error The repository's error; its message is written for the owner.
     */
    private fun rejectFailed(draft: RejectionDraft, error: Exception) {
        mutableState.value = state.value.copy(rejecting = draft.copy(inProgress = false,
            error = error.message ?: REJECT_FAILED))
    }

    /**
     * Replaces any running load with [block], showing a retryable error if it fails.
     * @param block The reads to run.
     */
    private fun load(block: suspend () -> Unit) {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = LOAD_FAILED)
            }
        }
    }

    /**
     * Records the selected campsite in state and saved state.
     * @param campsiteId The campsite, or null when the owner has none.
     */
    private fun choose(campsiteId: String?) {
        savedState[CAMPSITE_KEY] = campsiteId
        mutableState.value = state.value.copy(selectedCampsiteId = campsiteId)
    }

    /**
     * Reads one campsite's bookings and publishes the three lists and the calendar.
     * @param campsiteId The campsite, or null to show nothing.
     */
    private suspend fun loadBookings(campsiteId: String?) {
        val entries = if (campsiteId == null) emptyList() else repository.getCampsiteReservations(campsiteId)
        if (campsiteId != state.value.selectedCampsiteId) return
        val at = now()
        val booked = entries.filter { it.status == Reservation.STATUS_CONFIRMED }
            .flatMap { it.bookedNights() }.toSet()
        val requested = entries.filter { it.status == Reservation.STATUS_REQUESTED && it.endDate > at }
            .flatMap { it.bookedNights() }.toSet() - booked
        // Requested bookings are listed with confirmed ones, so new bookings show before confirmation.
        val active = entries.filter { it.isUpcomingForOwner(at) }
            .sortedWith(compareBy<Reservation> { it.startDate }.thenBy { it.id })
        // Open on the next booked stay so the owner sees booked nights right away.
        val month = state.value.month.ifEmpty { monthOf(active.firstOrNull()?.startDate ?: at) }
        mutableState.value = state.value.copy(
            loading = false,
            error = null,
            active = active,
            past = entries.filter { it.isPast(at) }
                .sortedWith(compareByDescending<Reservation> { it.endDate }.thenBy { it.id }),
            cancelled = entries.filter { it.isCancelled() }
                .sortedWith(compareByDescending<Reservation> { it.updatedAt }.thenBy { it.id }),
            bookedNights = booked,
            requestedNights = requested,
            month = month,
        )
    }

    private companion object {
        /** Saved-state key for the selected campsite. */
        const val CAMPSITE_KEY = "ownerReservationCampsite"
        /** Saved-state key for the selected tab. */
        const val TAB_KEY = "ownerReservationTab"
        /** Saved-state key for the month the owner moved to. */
        const val MONTH_KEY = "ownerReservationMonth"
        /** Shown when campsites or bookings cannot be read. */
        const val LOAD_FAILED = "Could not load your reservations. Check your connection and retry."
        /** Shown when a rejection fails for an unexpected reason. */
        const val REJECT_FAILED = "Could not reject the reservation. Check your connection and retry."
    }
}
