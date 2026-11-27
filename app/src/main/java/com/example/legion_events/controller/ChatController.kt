/*
 * ChatController.kt
 *
 * Purpose: Open a private conversation and coordinate drafts, sends, and explicit retries.
 * Role: MVC controller; owns lifecycle subscriptions and durable retry IDs, never announcements.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.ConversationSummary
import com.example.legion_events.data.MessageRepository
import com.example.legion_events.model.ChatMessage
import com.example.legion_events.model.MAX_CHAT_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Private chat state.
 * @property thread Loaded conversation membership and optional campsite label.
 * @property messages Committed messages in chronological order.
 * @property draft Unsent text, retained across recreation.
 * @property loading Whether conversation access is being checked.
 * @property error Conversation or message listener failure, or null.
 * @property sending Whether an explicit send is awaiting confirmation.
 * @property sendError Uncertain send result; Retry reuses the same message ID and body.
 */
data class ChatState(val thread: ConversationSummary? = null, val messages: List<ChatMessage> = emptyList(),
    val draft: String = "", val loading: Boolean = true, val error: String? = null,
    val sending: Boolean = false, val sendError: String? = null)

/**
 * Opens an existing thread or the stable thread for a campsite, without automatically sending text.
 * @property repository Owns all Firebase access and membership validation.
 * @property savedState Saves the draft, resolved thread ID, and any uncertain send's ID/body.
 * @property conversationId Existing conversation ID, or null when starting from campsite details.
 * @property campsiteId Campsite ID used only when starting a new thread.
 */
class ChatController(private val repository: MessageRepository, private val savedState: SavedStateHandle,
    private val conversationId: String? = null, private val campsiteId: String? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatState(draft = savedState["chatDraft"] ?: "",
        sendError = if (savedState.get<String>("pendingMessageId") != null) "Your last send was not confirmed. Retry send to check." else null))
    /** Read-only state rendered by ChatScreen. */
    val state = mutableState.asStateFlow()
    private var opening: Job? = null
    private var subscription: Job? = null
    private var visible = false

    init { retry() }

    /** Reloads access after a failure, preserving the draft and any uncertain send. */
    fun retry() {
        if (state.value.sending) return
        opening?.cancel(); subscription?.cancel()
        mutableState.value = state.value.copy(thread = null, messages = emptyList(), loading = true, error = null)
        opening = viewModelScope.launch {
            try {
                val id = savedState.get<String>("chatId") ?: conversationId ?: repository.startConversation(checkNotNull(campsiteId))
                val thread = checkNotNull(repository.getConversation(id)) { "Conversation unavailable." }
                savedState["chatId"] = id
                mutableState.value = state.value.copy(thread = thread, loading = false)
                listen()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(loading = false, error = "Could not open this private conversation. Check access or Retry.") }
        }
    }

    /** Resumes live message updates when the route becomes visible. */
    fun resume() { visible = true; listen() }
    /** Stops live message updates while hidden, without discarding the draft or pending send. */
    fun pause() { visible = false; subscription?.cancel() }

    /** Attaches exactly one selected-thread listener after access has been checked. */
    private fun listen() {
        subscription?.cancel()
        val id = state.value.thread?.conversation?.id ?: return
        if (!visible) return
        subscription = viewModelScope.launch {
            repository.observeMessages(id).catch { mutableState.value = state.value.copy(error = "Could not load this conversation's messages. Retry.") }
                .collect { mutableState.value = state.value.copy(messages = it, error = null) }
        }
    }

    /** Edits unsent text; an uncertain send must be resolved before its body can be changed. */
    fun setDraft(text: String) {
        if (state.value.sending || savedState.get<String>("pendingMessageId") != null || text.length > MAX_CHAT_LENGTH) return
        savedState["chatDraft"] = text
        mutableState.value = state.value.copy(draft = text)
    }

    /** Sends or explicitly retries one stable message; recreation never resubmits automatically. */
    fun send() {
        val current = state.value
        val thread = current.thread ?: return
        if (current.sending || current.loading) return
        val text = savedState.get<String>("pendingMessageText") ?: current.draft.trim()
        if (text.isEmpty() || text.length > MAX_CHAT_LENGTH) return
        val id = savedState.get<String>("pendingMessageId") ?: UUID.randomUUID().toString()
        savedState["pendingMessageId"] = id; savedState["pendingMessageText"] = text
        mutableState.value = current.copy(sending = true, sendError = null)
        viewModelScope.launch {
            try {
                repository.send(thread.conversation.id, id, text)
                savedState.remove<String>("pendingMessageId"); savedState.remove<String>("pendingMessageText")
                savedState["chatDraft"] = ""
                mutableState.value = state.value.copy(draft = "", sending = false, sendError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(sending = false, sendError = "Send was not confirmed. Retry send; it will not create a duplicate.") }
        }
    }
}
