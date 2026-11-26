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
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.IOException

/**
 * An open, undrawn lottery that the owner session should check.
 * @property id Lottery document ID.
 * @property registrationEndAt Saved deadline. A missing or malformed deadline is attempted and reported as an error.
 * @property updatedAt Last saved change. A new value lets a lottery whose draw permanently failed be attempted again.
 */
data class LocalLotteryDrawCandidate(val id: String, val registrationEndAt: Timestamp?, val updatedAt: Timestamp? = null)

/** A draw that failed only because entrants joined or left meanwhile; a later attempt can succeed. */
class LotteryWaitlistChangedException : IllegalStateException("The waiting list changed. The next check will retry the draw.")

/** Whether retrying a failed draw or listener can help. */
enum class LocalLotteryDrawFailureKind {
    /** Network, contention, or a concurrent waitlist change; the next check may succeed. */
    RETRYABLE,
    /** The security rules rejected the owner; retrying cannot help until the rules change. */
    PERMISSION_DENIED,
    /** The lottery's saved data cannot be drawn; retrying cannot help until it changes. */
    INVALID,
}

/**
 * A classified draw or listener failure, so callers need no Firestore error codes.
 * @property kind Whether a retry can succeed.
 */
class LocalLotteryDrawException(val kind: LocalLotteryDrawFailureKind, cause: Throwable) : Exception(cause.message, cause)

/** Classifies [error] using Firestore's code; unrecognized failures are treated as invalid data. */
fun classifyLocalLotteryDrawFailure(error: Throwable): LocalLotteryDrawException = when (error) {
    is LocalLotteryDrawException -> error
    is LotteryWaitlistChangedException, is IOException -> LocalLotteryDrawException(LocalLotteryDrawFailureKind.RETRYABLE, error)
    is FirebaseFirestoreException -> LocalLotteryDrawException(when (error.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> LocalLotteryDrawFailureKind.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED, FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.INTERNAL, FirebaseFirestoreException.Code.UNKNOWN,
        FirebaseFirestoreException.Code.CANCELLED, FirebaseFirestoreException.Code.ALREADY_EXISTS,
        -> LocalLotteryDrawFailureKind.RETRYABLE
        else -> LocalLotteryDrawFailureKind.INVALID
    }, error)
    else -> LocalLotteryDrawException(LocalLotteryDrawFailureKind.INVALID, error)
}

/** Supplies owned lotteries and commits a draw when its registration has ended. */
interface LocalLotteryDrawRepository {
    /** Watches the signed-in owner's lotteries, including saved deadline changes. Fails with [LocalLotteryDrawException]. */
    fun observeOwnedLotteries(ownerId: String): Flow<List<LocalLotteryDrawCandidate>>

    /**
     * Returns true when this call commits the draw and false when it is no longer eligible.
     * @throws LocalLotteryDrawException When the draw fails, classified by whether a retry can help.
     */
    suspend fun draw(lotteryId: String): Boolean

    /** Names the lottery for an owner-facing message, or returns null when no name is available. */
    suspend fun lotteryName(lotteryId: String): String?
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
        if (ownerId != auth.currentUser?.uid) {
            close(classifyLocalLotteryDrawFailure(IllegalStateException("The signed-in owner changed.")))
            return@callbackFlow
        }
        val listener = firestore.collection("lotteries").whereEqualTo("ownerId", ownerId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(classifyLocalLotteryDrawFailure(error))
                } else {
                    val result = runCatching {
                        check(ownerId == ownerId()) { "The signed-in owner changed." }
                        snapshot?.documents.orEmpty()
                            .filter { it.get("status") == "open" && it.get("winnersDrawn") != true }
                            .map {
                                LocalLotteryDrawCandidate(
                                    it.id, it.get("registrationEndAt") as? Timestamp, it.get("updatedAt") as? Timestamp,
                                )
                            }
                    }
                    result.onSuccess { trySend(it) }.onFailure { close(classifyLocalLotteryDrawFailure(it)) }
                }
            }
        awaitClose { listener.remove() }
    }

    /** Classifies any failure of [drawOnce] so the caller knows whether to retry. */
    override suspend fun draw(lotteryId: String): Boolean = try {
        drawOnce(lotteryId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw classifyLocalLotteryDrawFailure(error)
    }

    /** Commits the immutable draw record, waitlist results, offers, Inbox notices, and pending push events. */
    private suspend fun drawOnce(lotteryId: String): Boolean {
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
            if (entriesByUser.size != lottery.entrantCount) throw LotteryWaitlistChangedException()
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
        if (!exists() || getString("lotteryId") != lotteryId || getString("status") != "joined") {
            throw LotteryWaitlistChangedException()
        }
        return checkNotNull(getString("userId")?.takeIf { it.isNotBlank() && '/' !in it }) { "An entrant has no valid user ID." }
    }

    /** Uses the first campsite's name, read from the cache when offline, so failures stay identifiable. */
    override suspend fun lotteryName(lotteryId: String): String? {
        val lottery = firestore.collection("lotteries").document(lotteryId).get().await()
        val campsiteIds = (lottery.get("campsiteIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val first = campsiteIds.firstOrNull() ?: return null
        val name = firestore.collection("campsites").document(first).get().await()
            .getString("name")?.takeIf(String::isNotBlank) ?: return null
        return if (campsiteIds.size > 1) "$name +${campsiteIds.size - 1}" else name
    }

    /** Returns the authenticated owner's UID before reading or committing a draw. */
    private fun ownerId(): String = checkNotNull(auth.currentUser) { "Sign in before drawing lotteries." }.uid

    /** Supplies the ordinary Firebase client used by the Android app. */
    companion object {
        /** Shared repository authenticated as the currently signed-in Android user. */
        val instance by lazy { FirebaseLocalLotteryDrawRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
