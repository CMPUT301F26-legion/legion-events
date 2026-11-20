/*
 * LocalLotteryDrawRepository.kt
 * Purpose: Draws an owner's closed lotteries from the foreground Android app.
 * Role: Data layer using the ordinary Firebase Android SDK and owner-authorized rules.
 */
package com.example.legion_events.data

import com.example.legion_events.model.MAX_WAITLIST_LIMIT
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * An open, undrawn lottery that the owner session should check.
 * @property id Lottery document ID.
 * @property registrationEndAt Saved deadline. A missing or malformed deadline is attempted and reported as an error.
 */
data class LocalLotteryDrawCandidate(val id: String, val registrationEndAt: Timestamp?)

/** Supplies owned lotteries and commits a draw when its registration has ended. */
interface LocalLotteryDrawRepository {
    /** Watches the signed-in owner's lotteries, including saved deadline changes. */
    fun observeOwnedLotteries(ownerId: String): Flow<List<LocalLotteryDrawCandidate>>

    /** Returns true when this call commits the draw and false when it is no longer eligible. */
    suspend fun draw(lotteryId: String): Boolean
}

/**
 * Uses a server query followed by transaction reads to recheck every joined entry and the lottery.
 * The stored entrant count detects entries missed by a concurrent join or leave, requiring a fresh query.
 * @property auth Authenticates the owner without privileged backend credentials.
 * @property firestore Supplies the app's database and transactions.
 * @property now Supplies the local eligibility check. Security rules enforce the server deadline.
 */
class FirebaseLocalLotteryDrawRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = Timestamp::now,
) : LocalLotteryDrawRepository {
    /** Registers an owner-scoped listener and removes it when the foreground session ends. */
    override fun observeOwnedLotteries(ownerId: String): Flow<List<LocalLotteryDrawCandidate>> = callbackFlow {
        check(ownerId == ownerId()) { "The signed-in owner changed." }
        val listener = firestore.collection("lotteries").whereEqualTo("ownerId", ownerId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else {
                    val result = runCatching {
                        check(ownerId == ownerId()) { "The signed-in owner changed." }
                        snapshot?.documents.orEmpty()
                            .filter { it.get("status") == "open" && it.get("winnersDrawn") != true }
                            .map { LocalLotteryDrawCandidate(it.id, it.get("registrationEndAt") as? Timestamp) }
                    }
                    result.onSuccess { trySend(it) }.onFailure { close(it) }
                }
            }
        awaitClose { listener.remove() }
    }

    /** Commits the immutable draw record, waitlist results, offers, Inbox notices, and pending push events. */
    override suspend fun draw(lotteryId: String): Boolean {
        require(lotteryId.isNotBlank() && '/' !in lotteryId) { "A single lottery ID is required." }
        val ownerId = ownerId()
        val reference = firestore.collection("lotteries").document(lotteryId)
        val initial = reference.get(Source.SERVER).await()
        if (!initial.exists()) return false
        check(initial.getString("ownerId") == ownerId) { "Only the lottery owner can draw it." }
        if (initial.getBoolean("winnersDrawn") == true || initial.getString("status") != "open" ||
            checkNotNull(initial.getTimestamp("registrationEndAt")) > now()
        ) return false

        // The Android transaction API reads documents, so query the IDs first and recheck each inside it.
        val candidates = firestore.collection("waitlist").whereEqualTo("lotteryId", lotteryId)
            .whereEqualTo("status", "joined").limit((MAX_WAITLIST_LIMIT + 1).toLong())
            .get(Source.SERVER).await().documents
        check(candidates.size <= MAX_WAITLIST_LIMIT) { "The waiting list exceeds its supported size." }
        return firestore.runTransaction { transaction ->
            check(ownerId() == ownerId) { "The signed-in owner changed." }
            val snapshot = transaction.get(reference)
            if (!snapshot.exists()) return@runTransaction false
            val lottery = snapshot.toLottery()
            check(lottery.ownerId == ownerId) { "Only the lottery owner can draw it." }
            if (lottery.winnersDrawn || lottery.status != "open" || lottery.registrationEndAt > now()) {
                return@runTransaction false
            }
            check(lottery.winnerCount in 1..lottery.waitlistLimit && lottery.waitlistLimit <= MAX_WAITLIST_LIMIT) {
                "The lottery has an invalid winner count or waiting list limit."
            }
            val drawReference = firestore.collection("lotteryDraws").document(lotteryId)
            check(!transaction.get(drawReference).exists()) { "This lottery already has a draw record. Reload it." }
            val entries = candidates.map { transaction.get(it.reference) }
            val entriesByUser = entries.groupBy { entry -> entry.entrantId(lotteryId) }
            check(entriesByUser.size == lottery.entrantCount) {
                "The waiting list changed. The next check will retry the draw."
            }
            val winners = entriesByUser.keys.shuffled().take(lottery.winnerCount).toSet()
            val timestamp = FieldValue.serverTimestamp()
            transaction.set(drawReference, mapOf(
                "lotteryId" to lotteryId, "ownerId" to ownerId,
                "entrantIds" to entriesByUser.keys.toList(), "winnerIds" to winners.toList(),
                "createdAt" to timestamp,
            ))
            for ((userId, userEntries) in entriesByUser) {
                val won = userId in winners
                val eventId = "${lotteryId}_$userId"
                for (entry in userEntries) {
                    transaction.update(entry.reference, mapOf("status" to if (won) "won" else "lost", "updatedAt" to timestamp))
                }
                if (won) {
                    transaction.set(firestore.collection("offers").document(eventId), mapOf(
                        "lotteryId" to lotteryId, "userId" to userId, "campsiteIds" to lottery.campsiteIds,
                        "status" to "pending", "response" to null, "expiresAt" to lottery.bookingEndAt,
                        "createdAt" to timestamp, "updatedAt" to timestamp,
                    ))
                }
                val result = mapOf(
                    "lotteryId" to lotteryId, "toUserId" to userId, "type" to if (won) "lottery_won" else "lottery_lost",
                    "offerId" to if (won) eventId else null, "createdAt" to timestamp,
                )
                transaction.set(firestore.collection("notifications").document(eventId), result)
                transaction.set(firestore.collection("lotteryNotificationOutbox").document(eventId),
                    result + mapOf("deliveryStatus" to "pending", "updatedAt" to timestamp))
            }
            transaction.update(reference, mapOf("winnersDrawn" to true, "updatedAt" to timestamp))
            true
        }.await()
    }

    /** Rejects a withdrawn, unrelated, missing, or malformed entry during the draw's transaction. */
    private fun DocumentSnapshot.entrantId(lotteryId: String): String {
        check(exists() && getString("lotteryId") == lotteryId && getString("status") == "joined") {
            "The waiting list changed. The next check will retry the draw."
        }
        return checkNotNull(getString("userId")?.takeIf { it.isNotBlank() && '/' !in it }) { "An entrant has no valid user ID." }
    }

    /** Returns the authenticated owner's UID before reading or committing a draw. */
    private fun ownerId(): String = checkNotNull(auth.currentUser) { "Sign in before drawing lotteries." }.uid

    /** Supplies the ordinary Firebase client used by the Android app. */
    companion object {
        /** Shared repository authenticated as the currently signed-in Android user. */
        val instance by lazy { FirebaseLocalLotteryDrawRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
