/* Exposes the current entrant's lottery notifications to S07. */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.NotificationRepository
import com.example.legion_events.data.OwnerNoticeRepository
import com.example.legion_events.model.LotteryNotification
import com.example.legion_events.model.OwnerNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Loading, loaded, or failed state for the lottery inbox. */
sealed interface InboxState {
    data object Loading : InboxState

    /** The current lists of saved results and owner notices (US 02.10.01), either of which can be empty. */
    data class Loaded(
        val notifications: List<LotteryNotification>,
        val notices: List<OwnerNotice> = emptyList(),
    ) : InboxState

    data object Failed : InboxState
}

/** Subscribes to the active entrant's results and owner notices, and reconnects on Retry. */
class InboxController(
    private val userId: String,
    private val repository: NotificationRepository,
    private val notices: OwnerNoticeRepository? = null,
) : ViewModel() {
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
            // A notice failure must not hide lottery results, so it falls back to no notices.
            val received = notices?.observeReceived(userId)?.catch { emit(emptyList()) } ?: flowOf(emptyList())
            repository.observe(userId).combine(received) { results, sent -> InboxState.Loaded(results, sent) }
                .catch { mutableState.value = InboxState.Failed }
                .collect { mutableState.value = it }
        }
    }
}
