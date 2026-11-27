/*
 * Conversation.kt
 *
 * Purpose: Represent a private entrant–owner conversation and its messages.
 * Role: Models in MVC; keeps private chat separate from one-way announcements.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Maximum message length accepted by the existing Firestore rules. */
const val MAX_CHAT_LENGTH = 4000

/**
 * Membership and preview stored in conversations/{id}.
 * @property id Conversation document ID.
 * @property campsiteId Campsite the conversation concerns.
 * @property entrantId Entrant participant UID.
 * @property ownerId Campsite owner participant UID.
 * @property lastMessage Latest committed message preview.
 * @property createdAt Server creation time, or null while pending.
 * @property updatedAt Latest server update time, or null while pending.
 * @property lotteryId Optional related lottery ID.
 * @property reservationId Optional related reservation ID.
 */
data class Conversation(val id: String, val campsiteId: String, val entrantId: String, val ownerId: String,
    val lastMessage: String, val createdAt: Timestamp?, val updatedAt: Timestamp?,
    val lotteryId: String? = null, val reservationId: String? = null) {
    /** Returns the other participant, rejecting callers outside this conversation. */
    fun receiverFor(userId: String): String {
        require(entrantId != ownerId && userId in listOf(entrantId, ownerId)) { "This conversation is private." }
        return if (userId == entrantId) ownerId else entrantId
    }
}

/**
 * A message stored separately at messages/{id}; only its recipient can mark it read.
 * @property id Stable client-generated document ID, reused for retries.
 * @property conversationId Parent conversation ID.
 * @property senderId Authenticated sender UID.
 * @property receiverId Other participant UID.
 * @property text Message text, at most 4000 characters.
 * @property read Stored recipient read flag.
 * @property createdAt Server send time, or null while pending.
 * @property updatedAt Server update time, or null while pending.
 */
data class ChatMessage(val id: String, val conversationId: String, val senderId: String, val receiverId: String,
    val text: String, val read: Boolean, val createdAt: Timestamp?, val updatedAt: Timestamp?)
