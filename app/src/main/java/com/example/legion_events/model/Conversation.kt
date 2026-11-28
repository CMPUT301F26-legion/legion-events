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
 *
 * A campsite thread links an entrant with that campsite's owner. A peer-to-peer thread has no campsite;
 * its two users are stored in sorted order (entrantId < ownerId), so those fields carry no role.
 * @property id Conversation document ID.
 * @property campsiteId Campsite the conversation concerns, or null for a peer-to-peer thread.
 * @property entrantId Entrant participant UID, or the lower UID of a peer-to-peer thread.
 * @property ownerId Campsite owner participant UID, or the higher UID of a peer-to-peer thread.
 * @property lastMessage Latest committed message preview.
 * @property createdAt Server creation time, or null while pending.
 * @property updatedAt Latest server update time, or null while pending.
 * @property lotteryId Optional related lottery ID.
 * @property reservationId Optional related reservation ID.
 */
data class Conversation(val id: String, val campsiteId: String?, val entrantId: String, val ownerId: String,
    val lastMessage: String, val createdAt: Timestamp?, val updatedAt: Timestamp?,
    val lotteryId: String? = null, val reservationId: String? = null) {
    /** True for a thread between two users with no campsite; entrant/owner roles do not apply. */
    val isPeerToPeer: Boolean get() = campsiteId == null

    /** Returns the other participant, rejecting callers outside this conversation. */
    fun receiverFor(userId: String): String {
        require(entrantId != ownerId && userId in listOf(entrantId, ownerId)) { "This conversation is private." }
        return if (userId == entrantId) ownerId else entrantId
    }
}

/**
 * Returns the other participants of [userId]'s conversations that contain at least one message,
 * most recently active first, each listed once. Empty threads (preview still blank) are not contacts.
 * @param conversations Conversations [userId] belongs to; others are ignored.
 * @param userId The participant whose contacts are wanted.
 */
fun pastContacts(conversations: List<Conversation>, userId: String): List<String> =
    conversations.filter { userId in listOf(it.entrantId, it.ownerId) && it.entrantId != it.ownerId && it.lastMessage.isNotEmpty() }
        .sortedWith(compareByDescending<Conversation> { it.updatedAt }.thenBy { it.id })
        .map { it.receiverFor(userId) }
        .distinct()

/**
 * Lists everyone related to [userId]: past chat contacts first, in their given order, then entrants who
 * booked one of [userId]'s campsites but have never messaged, most recent booking first. Each UID appears once
 * and [userId] itself is never included.
 * @param userId The owner or entrant whose related users are wanted.
 * @param pastContacts Result of `MessageRepository.getPastContacts(userId)`.
 * @param campsiteReservations Bookings at [userId]'s campsites, in any status; others' bookings are ignored.
 */
fun listRelatedUsers(userId: String, pastContacts: List<String>, campsiteReservations: List<Reservation>): List<String> {
    val unmessaged = campsiteReservations.filter { it.ownerId == userId }
        .sortedWith(compareByDescending<Reservation> { it.createdAt }.thenBy { it.id })
        .map { it.userId }
    return (pastContacts + unmessaged).filter { it != userId }.distinct()
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
