/*
 * MessagesController.kt
 *
 * Purpose: Observe the current participant's private conversations.
 * Role: MVC controller for the separate Messages list; keeps listeners lifecycle-scoped.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ConversationSummary
import com.example.legion_events.data.MessageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Conversation list state.
 * @property conversations Threads involving the current participant.
 * @property loading Whether the first snapshot is pending.
 * @property error Recoverable listener failure, or null.
 */
data class MessagesState(val conversations: List<ConversationSummary> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** Loads private threads only while the list is visible. */
class MessagesController(private val repository: MessageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MessagesState())
    /** Read-only list state displayed by MessagesScreen. */
    val state = mutableState.asStateFlow()
    private var subscription: Job? = null
    /** Starts or explicitly retries one conversation subscription. */
    fun resume() {
        subscription?.cancel()
        mutableState.value = MessagesState()
        subscription = viewModelScope.launch {
            try { repository.observeConversations()
                .collect { mutableState.value = MessagesState(it, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = MessagesState(loading = false, error = "Could not load messages. Retry.") }
        }
    }
    /** Removes membership listeners when the screen is hidden. */
    fun pause() { subscription?.cancel() }
}
