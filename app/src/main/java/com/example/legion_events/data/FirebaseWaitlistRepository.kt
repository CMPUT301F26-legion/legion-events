/*
 * FirebaseWaitlistRepository.kt
 *
 * Purpose: Firestore implementation of joining and leaving waitlists (US 01.01.06, 01.02.01, 01.02.02).
 * Role: Data layer in MVC; writes `waitlist/{lotteryId}_{userId}` and the lottery's `entrantCount`.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Joins and leaves waitlists with the writes the deployed security rules allow.
 * The rules only let a user read their own entry if it exists, so the entry is found with a
 * query first, and each change updates the entry and the lottery's count in one transaction.
 * @property auth Supplies the signed-in user's ID.
 * @property firestore The app's Firestore database.
 * @property now Clock used to check the registration period; tests can replace it.
 */
class FirebaseWaitlistRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = { Timestamp.now() },
) : WaitlistRepository {
    override suspend fun getLotteriesForCampsite(campsiteId: String): List<LotteryEntry> {
        val lotteries = firestore.collection("lotteries").whereArrayContains("campsiteIds", campsiteId)
            .get().await().documents.mapNotNull { runCatching { it.toLottery() }.getOrNull() }
        val statuses = myEntries().associate { it.lotteryId to it.status }
        return lotteries.map { LotteryEntry(it, statuses[it.id]) }
            .sortedBy { it.lottery.registrationEndAt }
    }

    override suspend fun getMyEntries(): List<LotteryEntry> = coroutineScope {
        myEntries().map { entry ->
            async {
                val document = firestore.collection("lotteries").document(entry.lotteryId).get().await()
                val lottery = if (document.exists()) runCatching { document.toLottery() }.getOrNull() else null
                lottery?.let { LotteryEntry(it, entry.status, campsiteNames(it.campsiteIds)) }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.lottery.registrationEndAt }
    }

    override suspend fun join(lotteryId: String) {
        val userId = userId()
        val existing = myEntries().firstOrNull { it.lotteryId == lotteryId }
        check(existing?.status != WaitlistEntrant.STATUS_JOINED) { "You are already on this waitlist." }
        check(existing == null || existing.status == WaitlistEntrant.STATUS_WITHDRAWN) {
            "This lottery has already been drawn."
        }
        val lotteryReference = firestore.collection("lotteries").document(lotteryId)
        val entryReference = firestore.collection("waitlist").document("${lotteryId}_$userId")
        firestore.runTransaction { transaction ->
            val lottery = transaction.get(lotteryReference).toLottery()
            check(lottery.isRegistrationOpen(now())) { "Registration for this lottery is closed." }
            check(lottery.entrantCount < lottery.waitlistLimit) { "This waitlist is full." }
            if (existing == null) {
                transaction.set(entryReference, mapOf(
                    "lotteryId" to lotteryId,
                    "userId" to userId,
                    "status" to WaitlistEntrant.STATUS_JOINED,
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
            } else {
                transaction.update(entryReference, mapOf(
                    "status" to WaitlistEntrant.STATUS_JOINED,
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
            }
            transaction.update(lotteryReference, mapOf(
                "entrantCount" to lottery.entrantCount + 1,
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.await()
    }

    override suspend fun leave(lotteryId: String) {
        val userId = userId()
        val lotteryReference = firestore.collection("lotteries").document(lotteryId)
        val entryReference = firestore.collection("waitlist").document("${lotteryId}_$userId")
        firestore.runTransaction { transaction ->
            val lottery = transaction.get(lotteryReference).toLottery()
            val entry = transaction.get(entryReference)
            check(entry.exists() && entry.getString("status") == WaitlistEntrant.STATUS_JOINED) {
                "You are not on this waitlist."
            }
            check(lottery.isRegistrationOpen(now())) { "Registration for this lottery is closed." }
            transaction.update(entryReference, mapOf(
                "status" to WaitlistEntrant.STATUS_WITHDRAWN,
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
            transaction.update(lotteryReference, mapOf(
                "entrantCount" to lottery.entrantCount - 1,
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.await()
    }

    /** Reads every waitlist entry that belongs to the signed-in user. */
    private suspend fun myEntries(): List<WaitlistEntrant> =
        firestore.collection("waitlist").whereEqualTo("userId", userId()).get().await().documents.map { it.toEntry() }

    /** Looks up the names of the listed campsites, skipping any that are no longer readable. */
    private suspend fun campsiteNames(ids: List<String>): List<String> = coroutineScope {
        ids.map { id ->
            async {
                runCatching { firestore.collection("campsites").document(id).get().await().getString("name") }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /** The signed-in user's ID. */
    private fun userId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    companion object {
        /** Repository for the app's default Firebase project. */
        val instance: FirebaseWaitlistRepository by lazy {
            FirebaseWaitlistRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}

/** Reads one waitlist document. */
private fun DocumentSnapshot.toEntry() = WaitlistEntrant(
    userId = getString("userId").orEmpty(),
    status = getString("status").orEmpty(),
    joinedAt = getTimestamp("joinedAt"),
    lotteryId = getString("lotteryId").orEmpty(),
)
