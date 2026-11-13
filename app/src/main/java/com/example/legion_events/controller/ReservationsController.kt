/*
 * ReservationsController.kt
 *
 * Purpose: Load active and past reservations for S10 (US 01.06.01/02).
 * Role: Controller in MVC; filters bookings, keeps tab selection, and handles checkout.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The two stay periods available on S10. */
enum class ReservationTab { ACTIVE, PAST }

/**
 * State displayed in S10's Active and Past tabs.
 * @property entries Upcoming and ongoing confirmed stays, ordered by check-in and then ID.
 * @property pastEntries Finished confirmed stays, ordered by latest checkout and then ID.
 * @property selectedTab Stay period currently displayed.
 * @property loading Whether a read is pending.
 * @property error Safe request failure message, or null.
 */
data class ReservationsState(
    val entries: List<ReservationEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val pastEntries: List<ReservationEntry> = emptyList(),
    val selectedTab: ReservationTab = ReservationTab.ACTIVE,
)

/**
 * Loads the entrant's reservations when S10 resumes or the user requests Refresh.
 * @property repository Reads only the authenticated entrant's bookings.
 * @property savedState Retains the selected tab when the screen or process is recreated.
 * @property now Clock used to apply checkout boundaries; tests supply a fixed clock.
 */
class ReservationsController(
    private val repository: ReservationRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    /** List state changed only by this controller. */
    private val mutableState = MutableStateFlow(ReservationsState(selectedTab =
        ReservationTab.entries.firstOrNull { it.name == savedState.get<String>("reservationTab") }
            ?: ReservationTab.ACTIVE))
    /** Read-only list state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** In-flight read, cancelled before Refresh starts a replacement. */
    private var request: Job? = null
    /** Moves stays from Active to Past at checkout even when the screen remains open. */
    private var checkout: Job? = null

    /** Reloads owned bookings and starts at most one checkout timer for the current results. */
    fun refresh() {
        request?.cancel()
        checkout?.cancel()
        mutableState.value = ReservationsState(selectedTab = state.value.selectedTab)
        request = viewModelScope.launch {
            try {
                val entries = repository.getMyReservations()
                publishPeriods(entries)
                checkout = viewModelScope.launch {
                    while (state.value.entries.isNotEmpty()) {
                        val next = state.value.entries.minOf { it.reservation.endDate }
                        delay(maxOf(1L, next.toDate().time - now().toDate().time))
                        publishPeriods(entries)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ReservationsState(loading = false,
                    selectedTab = state.value.selectedTab,
                    error = "Could not load your reservations. Check your connection and retry.")
            }
        }
    }

    /** Changes the visible period without another database read and saves it for restoration. */
    fun selectTab(tab: ReservationTab) {
        savedState["reservationTab"] = tab.name
        mutableState.value = state.value.copy(selectedTab = tab)
    }

    /** Applies both model rules using one clock value, so checkout cannot leave a gap between tabs. */
    private fun publishPeriods(entries: List<ReservationEntry>) {
        val at = now()
        mutableState.value = ReservationsState(
            entries = entries.filter { it.reservation.isActive(at) }
                .sortedWith(compareBy<ReservationEntry> { it.reservation.startDate }.thenBy { it.reservation.id }),
            pastEntries = entries.filter { it.reservation.isPast(at) }
                .sortedWith(compareByDescending<ReservationEntry> { it.reservation.endDate }.thenBy { it.reservation.id }),
            selectedTab = state.value.selectedTab, loading = false)
    }
}
