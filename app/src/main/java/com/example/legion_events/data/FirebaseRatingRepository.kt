/*
 * FirebaseRatingRepository.kt
 *
 * Purpose: Load and save the entrant's campsite rating from a completed stay (US 01.13.01).
 * Role: Data layer in MVC; checks ownership, eligibility, and retry-safe Firestore writes.
 * Outstanding issues: Protected campsite rating aggregates need a trusted backend updater.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Rating
import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Uses the existing ratings/{campsiteId}_{uid} contract; callers cannot choose another UID.
 * @property auth Supplies the entrant's current identity.
 * @property firestore Reads stays, active campsites, and ratings; writes only the rating document.
 * @property now Clock for the checkout boundary, injectable in tests.
 */
class FirebaseRatingRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = { Timestamp.now() },
) : RatingRepository {
    /** Loads an eligible stay, its current campsite labels, and the entrant's existing score. */
    override suspend fun getForStay(reservationId: String): RatingDetails? {
        validateId(reservationId)
        val userId = currentUserId()
        try {
            val document = firestore.collection("reservations").document(reservationId).get().await()
            checkAccount(userId)
            val stay = document.eligibleStay(userId) ?: return null
            val campsite = firestore.collection("campsites").document(stay.campsiteId).get().await()
            checkAccount(userId)
            if (!campsite.exists() || campsite.get("active") != true) return null
            val saved = firestore.collection("ratings").document("${stay.campsiteId}_$userId").get().await()
            checkAccount(userId)
            return RatingDetails(ReservationEntry(stay,
                (campsite.get("name") as? String)?.takeIf(String::isNotBlank),
                (campsite.get("locationName") as? String)?.takeIf(String::isNotBlank)),
                saved.toRating(stay.campsiteId, userId))
        } catch (error: FirebaseFirestoreException) {
            checkAccount(userId)
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return null
            throw error
        }
    }

    /** Rechecks all reads on each transaction attempt and preserves existing data on retry or update. */
    override suspend fun saveForStay(reservationId: String, score: Int): Rating {
        validateId(reservationId)
        require(score in 1..5) { "A rating must be between 1 and 5." }
        val userId = currentUserId()
        val (reference, campsiteId) = firestore.runTransaction { transaction ->
            checkAccount(userId)
            val stay = checkNotNull(transaction.get(firestore.collection("reservations").document(reservationId))
                .eligibleStay(userId)) { "This stay is not available for rating." }
            val campsite = transaction.get(firestore.collection("campsites").document(stay.campsiteId))
            check(campsite.exists() && campsite.get("active") == true) { "This campsite is not available for rating." }
            val rating = firestore.collection("ratings").document("${stay.campsiteId}_$userId")
            val existing = transaction.get(rating).toRating(stay.campsiteId, userId)
            checkAccount(userId)
            when {
                existing == null -> transaction.set(rating, mapOf(
                    "campsiteId" to stay.campsiteId, "userId" to userId, "score" to score,
                    "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
                existing.score != score -> transaction.update(rating, mapOf(
                    "score" to score, "updatedAt" to FieldValue.serverTimestamp()))
                // An uncertain earlier commit may already have saved this score. Keep its timestamps.
            }
            rating to stay.campsiteId
        }.await()
        checkAccount(userId)
        val saved = reference.get(Source.SERVER).await()
        checkAccount(userId)
        return checkNotNull(saved.toRating(campsiteId, userId)) { "Please reload your rating." }
    }

    /** Checks the owner before mapping the booking, then applies the shared checkout rule. */
    private fun DocumentSnapshot.eligibleStay(userId: String): Reservation? {
        if (!exists() || get("userId") != userId) return null
        return toReservation().takeIf { it.canRate(now()) }
    }

    /** Rejects malformed or mismatched saved ratings rather than overwriting another record. */
    private fun DocumentSnapshot.toRating(campsiteId: String, userId: String): Rating? {
        if (!exists()) return null
        if (get("campsiteId") != campsiteId || get("userId") != userId) throw SecurityException("Rating ownership changed.")
        val score = checkNotNull(get("score") as? Long) { "Invalid rating score." }
        check(score in 1L..5L) { "Invalid rating score." }
        return Rating(campsiteId, userId, score.toInt(), ratingTimestamp("createdAt"), ratingTimestamp("updatedAt"))
    }

    /** Allows pending server timestamps but never interprets another type as a timestamp. */
    private fun DocumentSnapshot.ratingTimestamp(field: String): Timestamp? {
        val value = get(field)
        check(value == null || value is Timestamp) { "Invalid rating $field." }
        return value
    }

    /** Rejects paths and blank IDs before database access. */
    private fun validateId(id: String) { require(id.isNotBlank() && '/' !in id) { "A single reservation ID is required." } }

    /** Returns the active authentication UID before starting any Firebase operation. */
    private fun currentUserId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /** Prevents a completed read or transaction from being displayed after an account change. */
    private fun checkAccount(userId: String) {
        if (auth.currentUser?.uid != userId) throw SecurityException("The signed-in account changed.")
    }

    companion object {
        /** Rating service for the app's existing Firebase configuration. */
        val instance by lazy { FirebaseRatingRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
