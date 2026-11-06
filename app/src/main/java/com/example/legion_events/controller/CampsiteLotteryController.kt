/*
 * CampsiteLotteryController.kt
 *
 * Purpose: Lets an entrant join an open lottery from campsite details (US 01.01.06, 01.02.01).
 * Role: Controller in MVC; S04's lottery section renders its state and WaitlistRepository saves the entry.
 * Outstanding issues: Leaving is done from S06 My lotteries, not from S04.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.data.WaitlistRepository
import com.example.legion_events.data.isRegistrationOpen
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What S04's lottery section shows.
 * @property loading Whether the campsite's lotteries are loading.
 * @property lotteries Lotteries for this campsite whose registration is open now.
 * @property error Why the lotteries could not be loaded, or null.
 * @property joining ID of the lottery being joined, or null.
 * @property joinedLotteryId Lottery joined during this visit, used to show the S06b confirmation.
 * @property actionError Why the last join failed, or null.
 */
data class CampsiteLotteryState(
    val loading: Boolean = true,
    val lotteries: List<LotteryEntry> = emptyList(),
    val error: String? = null,
    val joining: String? = null,
    val joinedLotteryId: String? = null,
    val actionError: String? = null,
)

/**
 * Loads the campsite's open lotteries and joins one waitlist at a time.
 * @property campsiteId The campsite shown on S04.
 * @property repository Reads lotteries and saves waitlist entries.
 * @property now Clock used to hide lotteries whose registration is not open.
 */
class CampsiteLotteryController(
    private val campsiteId: String,
    private val repository: WaitlistRepository,
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(CampsiteLotteryState())
    /** State collected by S04. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the campsite's lotteries and the entrant's status in each. */
    fun retry() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val open = repository.getLotteriesForCampsite(campsiteId).filter { it.lottery.isRegistrationOpen(now()) }
                mutableState.value = state.value.copy(loading = false, lotteries = open)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false,
                    error = "Could not load this campsite's lotteries. Please retry.")
            }
        }
    }

    /**
     * Joins a lottery's waitlist; ignored while another join is running.
     * @param lotteryId The lottery to join.
     */
    fun join(lotteryId: String) {
        if (state.value.joining != null) return
        mutableState.value = state.value.copy(joining = lotteryId, actionError = null, joinedLotteryId = null)
        viewModelScope.launch {
            try {
                repository.join(lotteryId)
                mutableState.value = state.value.copy(joining = null, joinedLotteryId = lotteryId)
                retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                mutableState.value = state.value.copy(joining = null, actionError = error.message)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(joining = null,
                    actionError = "Could not join the waitlist. Check your connection and retry.")
            }
        }
    }
}
