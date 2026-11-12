package com.example.legion_events.controller

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

/**
 * State displayed in S10's Active tab.
 * @property entries Upcoming and ongoing confirmed stays, ordered by check-in and then ID.
 * @property loading Whether a read is pending.
 * @property error Safe request failure message, or null.
 */
data class ReservationsState(
    val entries: List<ReservationEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads the entrant's active reservations when S10 resumes or the user requests Refresh.
 * @property repository Reads only the authenticated entrant's bookings.
 * @property now Clock used to apply checkout boundaries; tests supply a fixed clock.
 */
class ReservationsController(
    private val repository: ReservationRepository,
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    /** List state changed only by this controller. */
    private val mutableState = MutableStateFlow(ReservationsState())
    /** Read-only list state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** In-flight read, cancelled before Refresh starts a replacement. */
    private var request: Job? = null
    /** Removes stays at checkout even when the screen remains open. */
    private var checkout: Job? = null

    /** Reloads owned bookings and starts at most one checkout timer for the current results. */
    fun refresh() {
        request?.cancel()
        checkout?.cancel()
        mutableState.value = ReservationsState()
        request = viewModelScope.launch {
            try {
                val entries = repository.getMyReservations()
                publishActive(entries)
                checkout = viewModelScope.launch {
                    while (state.value.entries.isNotEmpty()) {
                        val next = state.value.entries.minOf { it.reservation.endDate }
                        delay(maxOf(1L, next.toDate().time - now().toDate().time))
                        publishActive(entries)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ReservationsState(loading = false,
                    error = "Could not load your reservations. Check your connection and retry.")
            }
        }
    }

    /** Applies the shared model's active rule using one clock value for the entire list. */
    private fun publishActive(entries: List<ReservationEntry>) {
        val at = now()
        mutableState.value = ReservationsState(entries = entries.filter { it.reservation.isActive(at) }
            .sortedWith(compareBy<ReservationEntry> { it.reservation.startDate }.thenBy { it.reservation.id }), loading = false)
    }
}
