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
 * One person on the Messages screen, in [com.example.legion_events.model.listRelatedUsers] order.
 * @property userId The other user's UID; profiles are private, so no name is shown.
 * @property role "Owner", "Entrant", or "Contact" when the newest thread is peer-to-peer.
 * @property campsiteNames Campsites of existing threads with this person; empty when never messaged.
 * @property conversationId Most recently active thread with this person, or null when never messaged.
 */
data class RelatedUserRow(val userId: String, val role: String, val campsiteNames: List<String>, val conversationId: String?)

/**
 * Conversation list state.
 * @property conversations Threads involving the current participant.
 * @property loading Whether the first snapshot is pending.
 * @property error Recoverable listener failure, or null.
 * @property relatedUserIds Past contacts, then unmessaged entrants of the viewer's campsites.
 * @property relatedError Recoverable failure loading related users, or null; threads still show.
 */
data class MessagesState(val conversations: List<ConversationSummary> = emptyList(), val loading: Boolean = true, val error: String? = null,
    val relatedUserIds: List<String> = emptyList(), val relatedError: String? = null) {
    /** Joins related users with live threads; newest thread first because conversations are sorted that way. */
    val relatedUsers: List<RelatedUserRow> get() = relatedUserIds.map { uid ->
        val threads = conversations.filter { it.conversation.lastMessage.isNotEmpty() && uid in listOf(it.conversation.entrantId, it.conversation.ownerId) }
        val role = threads.firstOrNull()?.conversation?.let { when { it.isPeerToPeer -> "Contact"; it.ownerId == uid -> "Owner"; else -> "Entrant" } } ?: "Entrant"
        RelatedUserRow(uid, role, threads.mapNotNull { it.campsiteName }.distinct(), threads.firstOrNull()?.conversation?.id)
    }
}

/**
 * Loads private threads and related users only while the list is visible.
 * @property repository Supplies live conversations.
 * @property relatedUsers Reads the signed-in user's related UIDs, e.g. via data.loadRelatedUsers.
 */
class MessagesController(private val repository: MessageRepository,
    private val relatedUsers: suspend () -> List<String> = { emptyList() }) : ViewModel() {
    private val mutableState = MutableStateFlow(MessagesState())
    /** Read-only list state displayed by MessagesScreen. */
    val state = mutableState.asStateFlow()
    private var subscription: Job? = null
    private var related: Job? = null
    /** Starts or explicitly retries the conversation subscription and the related-users read. */
    fun resume() {
        subscription?.cancel(); related?.cancel()
        mutableState.value = MessagesState(relatedUserIds = state.value.relatedUserIds)
        subscription = viewModelScope.launch {
            try { repository.observeConversations()
                .collect { mutableState.value = state.value.copy(conversations = it, loading = false, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(conversations = emptyList(), loading = false, error = "Could not load messages. Retry.") }
        }
        related = viewModelScope.launch {
            try { mutableState.value = state.value.copy(relatedUserIds = relatedUsers(), relatedError = null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(relatedError = "Could not load people. Retry.") }
        }
    }
    /** Removes membership listeners when the screen is hidden. */
    fun pause() { subscription?.cancel(); related?.cancel() }
}
