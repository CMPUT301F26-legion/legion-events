/* Reads the authenticated entrant's inbox as lottery results arrive. */
package com.example.legion_events.data

import com.example.legion_events.model.LotteryNotification
import com.example.legion_events.model.LotteryResult
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Supplies lottery notifications for an authenticated recipient. */
fun interface NotificationRepository {
    /** Observes the recipient's lottery results in newest-first order. */
    fun observe(userId: String): Flow<List<LotteryNotification>>
}

/** Watches only inbox documents addressed to the active Firebase user. */
class FirebaseNotificationRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : NotificationRepository {
    /** Registers a recipient-scoped Firestore listener and removes it when collection ends. */
    override fun observe(userId: String): Flow<List<LotteryNotification>> = callbackFlow {
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
                val results = snapshot?.documents.orEmpty().mapNotNull { document ->
                    val result = LotteryResult.fromStoredType(document.get("type") as? String) ?: return@mapNotNull null
                    val createdAt = document.get("createdAt") as? Timestamp ?: return@mapNotNull null
                    val offerId = if (result == LotteryResult.WON) document.get("offerId") as? String else null
                    LotteryNotification(document.id, result, offerId, createdAt)
                }
                trySend(results.sortedWith(compareByDescending<LotteryNotification> { it.createdAt }.thenBy { it.id }))
            }
        awaitClose { listener.remove() }
    }

    companion object {
        /** Repository for the app's default Firebase project. */
        val instance by lazy { FirebaseNotificationRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
