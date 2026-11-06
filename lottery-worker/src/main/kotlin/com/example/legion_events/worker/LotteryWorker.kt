/*
 * LotteryWorker.kt
 *
 * Purpose: Draws winners after registration closes and records each result once (US 02.05.01).
 * Role: Backend worker for lottery results, booking offers, and notification events.
 */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import java.util.concurrent.ExecutionException

/** Largest joined waitlist supported by the lottery schema. */
private const val MAX_WAITLIST_LIMIT = 100

/**
 * Draws up to the configured number of unique winners for each due lottery.
 * A single transaction saves the results, offers, and notification events for each lottery.
 *
 * @property firestore Trusted Firestore client used to read and commit draws.
 */
class LotteryWorker(private val firestore: Firestore) {
    /**
     * Attempts all eligible draws and collects failures so other lotteries can still complete.
     *
     * @param cutoff Latest registration deadline eligible for this run, inclusive.
     * @return Number of draws committed by this run, including draws with empty waitlists.
     * @throws IllegalStateException If any draw failed, with individual failures attached.
     */
    fun drawDueLotteries(cutoff: Timestamp = Timestamp.now()): Int {
        val lotteries = firestore.collection("lotteries")
            .whereEqualTo("winnersDrawn", false).get().get().documents
        val failures = mutableListOf<Exception>()
        var drawn = 0
        for (lottery in lotteries) {
            try {
                if (lottery.isDue(cutoff) && drawLottery(lottery.reference, cutoff)) drawn++
            } catch (failure: ExecutionException) {
                failures += IllegalStateException("Draw failed for lottery ${lottery.id}.", failure.cause ?: failure)
            } catch (failure: RuntimeException) {
                failures += IllegalStateException("Draw failed for lottery ${lottery.id}.", failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Completed $drawn draw(s); ${failures.size} lottery draw(s) failed.")
                .also { summary -> failures.forEach(summary::addSuppressed) }
        }
        return drawn
    }

    /**
     * Rechecks eligibility in the transaction so concurrent attempts can commit a draw only once.
     *
     * @param reference Lottery document to read and update.
     * @param cutoff Registration deadline cutoff used throughout the run.
     * @return Whether this transaction committed a draw.
     * @throws ExecutionException If Firestore cannot complete the transaction.
     */
    private fun drawLottery(reference: DocumentReference, cutoff: Timestamp): Boolean =
        firestore.runTransaction { transaction ->
            val lottery = transaction.get(reference).get()
            if (!lottery.isDue(cutoff)) return@runTransaction false

            val winnerCount = lottery.getLong("winnerCount")
            check(winnerCount != null && winnerCount in 1L..MAX_WAITLIST_LIMIT.toLong()) { "Invalid winnerCount." }
            val expiresAt = checkNotNull(lottery.getTimestamp("bookingEndAt")) { "Missing bookingEndAt." }
            val campsiteIds = lottery.get("campsiteIds") as? List<*>
            check(campsiteIds != null && campsiteIds.size in 1..8 && campsiteIds.all { it is String && it.isNotBlank() }) {
                "Invalid campsiteIds."
            }

            // Read one extra entry so an oversized waitlist fails instead of drawing from a partial list.
            val waitlist = firestore.collection("waitlist").whereEqualTo("lotteryId", lottery.id)
                .whereEqualTo("status", "joined").limit(MAX_WAITLIST_LIMIT + 1)
            val entries = transaction.get(waitlist).get().documents
            check(entries.size <= MAX_WAITLIST_LIMIT) { "Joined entries exceed the supported waitlist limit." }
            // Group by UID so duplicate entries share one result and give the user one chance to win.
            val entriesByUser = entries.groupBy { entry ->
                val userId = entry.getString("userId")
                check(userId != null && userId.isNotBlank() && '/' !in userId) { "Invalid waitlist userId." }
                userId
            }
            val winners = entriesByUser.keys.shuffled().take(winnerCount.toInt()).toSet()

            for ((userId, userEntries) in entriesByUser) {
                val won = userId in winners
                for (entry in userEntries) {
                    transaction.update(entry.reference, "status", if (won) "won" else "lost")
                }

                val offerId = if (won) "${lottery.id}_$userId" else null
                if (offerId != null) {
                    transaction.create(
                        firestore.collection("offers").document(offerId),
                        mapOf(
                            "lotteryId" to lottery.id,
                            "userId" to userId,
                            "campsiteIds" to campsiteIds,
                            "status" to "pending",
                            "response" to null,
                            "expiresAt" to expiresAt,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                }
                sendNotification(transaction, firestore, lottery.id, userId, offerId)
            }

            transaction.update(reference, "winnersDrawn", true, "updatedAt", FieldValue.serverTimestamp())
            true
        }.get()

    /**
     * Checks whether this snapshot represents an open, undrawn lottery whose registration has ended.
     *
     * @param cutoff Time used to determine whether registration has closed.
     * @return Whether the lottery is eligible for this run.
     */
    private fun DocumentSnapshot.isDue(cutoff: Timestamp): Boolean =
        exists() && getBoolean("winnersDrawn") == false && getString("status") == "open" &&
            checkNotNull(getTimestamp("registrationEndAt")) { "Missing registrationEndAt." } <= cutoff
}
