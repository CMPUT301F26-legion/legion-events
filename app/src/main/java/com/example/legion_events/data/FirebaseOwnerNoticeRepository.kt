/*
 * FirebaseOwnerNoticeRepository.kt
 *
 * Purpose: Firestore implementation of owner notices (US 02.10.01, 02.10.02).
 * Role: Data layer in MVC; writes and reads `ownerNotices/{id}`.
 * Outstanding issues: Needs the `ownerNotices` security rule described in SCHEMA.md.
 */
package com.example.legion_events.data

import com.example.legion_events.model.MAX_NOTICE_LENGTH
import com.example.legion_events.model.OwnerNotice
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await

/**
 * Stores each notice once with the list of entrants who were on the waitlist when it was sent.
 * @property auth Supplies the signed-in user's ID.
 * @property firestore The app's Firestore database.
 * @property lotteries Reads the waitlist that receives a notice.
 */
class FirebaseOwnerNoticeRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val lotteries: LotteryRepository,
) : OwnerNoticeRepository {
    override suspend fun sendNotification(lotteryId: String, message: String): Int {
        val ownerId = userId()
        val text = message.trim()
        require(text.isNotEmpty() && text.length <= MAX_NOTICE_LENGTH) { "Write a notice of 1 to $MAX_NOTICE_LENGTH characters." }
        val recipients = lotteries.getWaitlist(lotteryId)
            .filter { it.status == WaitlistEntrant.STATUS_JOINED }.map { it.userId }.distinct()
        check(recipients.isNotEmpty()) { "Nobody is on this waitlist yet." }
        firestore.collection(COLLECTION).document().set(mapOf(
            "ownerId" to ownerId,
            "lotteryId" to lotteryId,
            "message" to text,
            "recipientIds" to recipients,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
        return recipients.size
    }

    override suspend fun getSentNotices(): List<OwnerNotice> =
        firestore.collection(COLLECTION).whereEqualTo("ownerId", userId()).get().await()
            .documents.map { it.toNotice() }.sortedByDescending { it.createdAt }

    override fun observeReceived(userId: String): Flow<List<OwnerNotice>> {
        val notices = callbackFlow {
            val listener = firestore.collection(COLLECTION).whereArrayContains("recipientIds", userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) close(error)
                    else trySend(snapshot?.documents.orEmpty().map { it.toNotice() }.sortedByDescending { it.createdAt })
                }
            awaitClose { listener.remove() }
        }
        // Follow the S11 opt-out so turning notifications off hides owner notices right away.
        val enabled = callbackFlow {
            val listener = firestore.collection("users").document(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) close(error) else trySend(snapshot?.getBoolean("notificationsEnabled") ?: true)
                }
            awaitClose { listener.remove() }
        }
        return combine(notices, enabled) { received, on -> if (on) received else emptyList() }
    }

    /** The signed-in user's ID. */
    private fun userId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    companion object {
        /** Firestore collection that holds the notices. */
        private const val COLLECTION = "ownerNotices"

        /** Repository for the app's default Firebase project. */
        val instance: FirebaseOwnerNoticeRepository by lazy {
            FirebaseOwnerNoticeRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance(),
                FirebaseLotteryRepository.instance)
        }
    }
}

/** Reads one notice document. */
private fun DocumentSnapshot.toNotice() = OwnerNotice(
    id = id,
    ownerId = getString("ownerId").orEmpty(),
    lotteryId = getString("lotteryId").orEmpty(),
    message = getString("message").orEmpty(),
    recipientIds = (get("recipientIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    createdAt = getTimestamp("createdAt"),
)
