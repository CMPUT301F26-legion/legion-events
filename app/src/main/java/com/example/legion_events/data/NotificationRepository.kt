/*
 * NotificationRepository.kt
 *
 * Purpose: Observe owned lottery results and completed cancellation notices.
 * Role: Data layer in MVC; keeps the shared Inbox query scoped to the signed-in recipient.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.LotteryNotification
import com.example.legion_events.model.LotteryResult
import com.example.legion_events.model.InboxNotification
import com.example.legion_events.model.CancellationNotification
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Supplies saved lottery results and completed cancellation notices for an authenticated recipient. */
fun interface NotificationRepository {
    /** Observes the recipient's saved notifications in newest-first order. */
    fun observe(userId: String): Flow<List<InboxNotification>>
}

/** Watches only inbox documents addressed to the active Firebase user. */
class FirebaseNotificationRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : NotificationRepository {
    /** Registers a recipient-scoped Firestore listener and removes it when collection ends. */
    override fun observe(userId: String): Flow<List<InboxNotification>> = callbackFlow {
        check(auth.currentUser?.uid == userId) { "The inbox requires the signed-in account." }
        val listener = firestore.collection("notifications").whereEqualTo("toUserId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (auth.currentUser?.uid != userId) {
                    close(SecurityException("The signed-in account changed."))
                    return@addSnapshotListener
                }
                val results = snapshot?.documents.orEmpty().mapNotNull { it.toInboxNotification(userId) }
                trySend(results.sortedWith(compareByDescending<InboxNotification> { it.createdAt }.thenBy { it.id }))
            }
        awaitClose { listener.remove() }
    }

    companion object {
        /** Repository for the app's default Firebase project. */
        val instance by lazy { FirebaseNotificationRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}

/** Maps known, complete notifications for this recipient, ignoring unsupported or malformed records. */
internal fun DocumentSnapshot.toInboxNotification(userId: String): InboxNotification? {
    if (get("toUserId") != userId) return null
    val createdAt = get("createdAt") as? Timestamp ?: return null
    if (get("type") == "reservation_cancelled") {
        val reservationId = (get("reservationId") as? String)?.takeIf { it.isNotBlank() && '/' !in it } ?: return null
        return CancellationNotification(id, reservationId, createdAt)
    }
    val result = LotteryResult.fromStoredType(get("type") as? String) ?: return null
    val offerId = if (result == LotteryResult.WON) get("offerId") as? String else null
    return LotteryNotification(id, result, offerId, createdAt)
}
