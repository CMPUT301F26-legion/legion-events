/* Exposes the current entrant's lottery notifications to S07. */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.NotificationRepository
import com.example.legion_events.model.LotteryNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Loading, loaded, or failed state for the lottery inbox. */
sealed interface InboxState {
    data object Loading : InboxState

    /** The current list of saved results, which can be empty. */
    data class Loaded(val notifications: List<LotteryNotification>) : InboxState

    data object Failed : InboxState
}

/** Subscribes to the active entrant's results and reconnects on Retry. */
class InboxController(private val userId: String, private val repository: NotificationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<InboxState>(InboxState.Loading)
    val state = mutableState.asStateFlow()
    private var subscription: Job? = null

    init {
        retry()
    }

    /** Replaces the previous subscription with a fresh inbox listener. */
    fun retry() {
        subscription?.cancel()
        mutableState.value = InboxState.Loading
        subscription = viewModelScope.launch {
            repository.observe(userId)
                .catch { mutableState.value = InboxState.Failed }
                .collect { mutableState.value = InboxState.Loaded(it) }
        }
    }
}
