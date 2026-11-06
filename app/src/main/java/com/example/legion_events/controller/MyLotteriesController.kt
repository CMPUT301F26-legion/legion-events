/*
 * MyLotteriesController.kt
 *
 * Purpose: Lists the entrant's lottery entries and lets them leave a waitlist (US 01.02.02).
 * Role: Controller in MVC; S06 renders its state and WaitlistRepository saves the change.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.data.WaitlistRepository
import com.example.legion_events.data.isRegistrationOpen
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What S06's entry list shows.
 * @property loading Whether entries are loading.
 * @property entries Every lottery the entrant joined, left, won, or lost.
 * @property error Why the entries could not be loaded, or null.
 * @property confirmingLeave Lottery whose leave confirmation dialog is open, or null.
 * @property leaving Whether a leave request is running.
 * @property actionError Why the last leave failed, or null.
 */
data class MyLotteriesState(
    val loading: Boolean = true,
    val entries: List<LotteryEntry> = emptyList(),
    val error: String? = null,
    val confirmingLeave: String? = null,
    val leaving: Boolean = false,
    val actionError: String? = null,
)

/**
 * Loads the entrant's entries and confirms before leaving a waitlist.
 * @property repository Reads entries and saves the withdrawal.
 * @property now Clock used to decide whether leaving is still allowed.
 */
class MyLotteriesController(
    private val repository: WaitlistRepository,
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(MyLotteriesState())
    /** State collected by S06. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the entrant's entries. */
    fun retry() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                mutableState.value = state.value.copy(loading = false, entries = repository.getMyEntries())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false,
                    error = "Could not load your lottery entries. Please retry.")
            }
        }
    }

    /**
     * Whether the entrant can still leave this entry's waitlist.
     * @param entry An entry from [MyLotteriesState.entries].
     * @return True while they are on the waitlist and registration is open.
     */
    fun canLeave(entry: LotteryEntry): Boolean =
        entry.status == WaitlistEntrant.STATUS_JOINED && entry.lottery.isRegistrationOpen(now())

    /**
     * Opens the leave confirmation for a lottery.
     * @param lotteryId The lottery to leave.
     */
    fun requestLeave(lotteryId: String) {
        if (!state.value.leaving) mutableState.value = state.value.copy(confirmingLeave = lotteryId, actionError = null)
    }

    /** Closes the confirmation without leaving. */
    fun cancelLeave() {
        if (!state.value.leaving) mutableState.value = state.value.copy(confirmingLeave = null)
    }

    /** Leaves the confirmed lottery's waitlist, then reloads the list. */
    fun confirmLeave() {
        val lotteryId = state.value.confirmingLeave ?: return
        if (state.value.leaving) return
        mutableState.value = state.value.copy(leaving = true)
        viewModelScope.launch {
            try {
                repository.leave(lotteryId)
                mutableState.value = state.value.copy(leaving = false, confirmingLeave = null)
                retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                mutableState.value = state.value.copy(leaving = false, confirmingLeave = null, actionError = error.message)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(leaving = false, confirmingLeave = null,
                    actionError = "Could not leave the waitlist. Check your connection and retry.")
            }
        }
    }
}
