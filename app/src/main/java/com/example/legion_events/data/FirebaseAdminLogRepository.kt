/*
 * FirebaseAdminLogRepository.kt
 *
 * Purpose: Firestore implementation of the S20 admin logs (US 03.08.01, 03.12.01).
 * Role: Data layer in MVC; reads `notifications`, `ownerNotices`, `conversations` and `messages`,
 *   which the security rules let an admin read in full.
 * Outstanding issues: Reads whole collections; fine for a course-sized database, but a large one
 *   would need paging.
 */
package com.example.legion_events.data

import com.example.legion_events.model.AdminLogCategory
import com.example.legion_events.model.AdminLogEntry
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Reads the logs from Firestore. Field names follow the security rules.
 * @property firestore The app's Firestore database.
 */
class FirebaseAdminLogRepository(private val firestore: FirebaseFirestore) : AdminLogRepository {
    override suspend fun getNotifications(): List<AdminLogEntry> =
        firestore.collection("notifications").get().await().documents.map { it.toNotificationEntry() }

    override suspend fun getOwnerMessages(): List<AdminLogEntry> = coroutineScope {
        val notices = async { firestore.collection("ownerNotices").get().await().documents }
        val conversations = async { firestore.collection("conversations").get().await().documents }
        val messages = async { firestore.collection("messages").get().await().documents }
        val owners = conversationOwners(conversations.await())
        notices.await().map { it.toNoticeEntry() } +
            messages.await().mapNotNull { it.toOwnerMessageEntry(owners) }
    }

    companion object {
        /** Repository for the app's default Firebase project. */
        val instance: FirebaseAdminLogRepository by lazy { FirebaseAdminLogRepository(FirebaseFirestore.getInstance()) }
    }
}

/**
 * Maps each campsite conversation's ID to its owner UID. Peer-to-peer threads (no campsite) are left out:
 * they have no owner role, so their messages are not owner messages.
 */
internal fun conversationOwners(conversations: List<DocumentSnapshot>): Map<String, String?> =
    conversations.filter { it.getString("campsiteId") != null }.associate { it.id to it.getString("ownerId") }

/** Maps a `notifications/{id}` document; lottery results have no sender or body. */
internal fun DocumentSnapshot.toNotificationEntry() = AdminLogEntry(
    id = id,
    category = AdminLogCategory.NOTIFICATION,
    type = getString("type").orEmpty(),
    fromUserId = getString("fromUserId"),
    toUserIds = listOfNotNull(getString("toUserId")),
    text = getString("body") ?: getString("title").orEmpty(),
    lotteryId = getString("lotteryId"),
    createdAt = getTimestamp("createdAt"),
)

/** Maps an `ownerNotices/{id}` broadcast. */
internal fun DocumentSnapshot.toNoticeEntry() = AdminLogEntry(
    id = id,
    category = AdminLogCategory.MESSAGE,
    type = AdminLogEntry.TYPE_OWNER_NOTICE,
    fromUserId = getString("ownerId"),
    toUserIds = (get("recipientIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    text = getString("message").orEmpty(),
    lotteryId = getString("lotteryId"),
    createdAt = getTimestamp("createdAt"),
)

/**
 * Maps a `messages/{id}` document when its conversation's owner sent it, or returns null for an
 * entrant's reply.
 * @param owners Conversation ID to that conversation's owner UID.
 */
internal fun DocumentSnapshot.toOwnerMessageEntry(owners: Map<String, String?>): AdminLogEntry? {
    val sender = getString("senderId") ?: return null
    if (owners[getString("conversationId")] != sender) return null
    return AdminLogEntry(
        id = id,
        category = AdminLogCategory.MESSAGE,
        type = AdminLogEntry.TYPE_DIRECT_MESSAGE,
        fromUserId = sender,
        toUserIds = listOfNotNull(getString("receiverId")),
        text = getString("text").orEmpty(),
        lotteryId = null,
        createdAt = getTimestamp("createdAt"),
    )
}
