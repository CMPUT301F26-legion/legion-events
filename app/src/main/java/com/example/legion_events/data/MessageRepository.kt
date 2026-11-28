/*
 * MessageRepository.kt
 *
 * Purpose: Define private entrant–owner chat access independently of announcements.
 * Role: Data contract in MVC; controllers depend on this interface instead of Firebase.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Conversation
import com.example.legion_events.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * A private thread with optional current campsite label and the viewing participant's UID.
 * @property conversation Stored membership and preview.
 * @property campsiteName Current campsite name, or null when unavailable or for a peer-to-peer thread.
 * @property myId Authenticated participant; used to distinguish sent messages in the view.
 */
data class ConversationSummary(val conversation: Conversation, val campsiteName: String?, val myId: String)

/** Uses only the authenticated identity; UIs never choose the sender or recipient UID. */
interface MessageRepository {
    /** Observes conversations where the current user is either the entrant or the owner. */
    fun observeConversations(): Flow<List<ConversationSummary>>
    /** Opens one stable entrant–owner thread for an active campsite, reusing it on retry. */
    suspend fun startConversation(campsiteId: String): String
    /**
     * Opens one stable thread with another user, with no campsite; either user starting it reaches the same thread.
     * @param otherUserId The other user's UID; must differ from the signed-in user and belong to an active account.
     * @return The conversation ID.
     */
    suspend fun startPeerToPeerConversation(otherUserId: String): String
    /** Reads a participant-owned thread, or null when absent or inaccessible. */
    suspend fun getConversation(conversationId: String): ConversationSummary?
    /** Observes only this participant's selected thread, in chronological order. */
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    /**
     * Reads the UIDs of everyone [userId] has exchanged at least one message with, most recent first.
     * @param userId Must be the signed-in user; conversations are private to their participants.
     * @throws SecurityException If [userId] is not the signed-in user.
     */
    suspend fun getPastContacts(userId: String): List<String>
    /** Atomically sends text and updates the preview; reuse messageId to retry an uncertain send. */
    suspend fun send(conversationId: String, messageId: String, text: String)
}
