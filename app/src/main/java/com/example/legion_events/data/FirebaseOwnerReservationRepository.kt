/*
 * FirebaseOwnerReservationRepository.kt
 *
 * Purpose: Firestore implementation of S17's owner reservations
 *   (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: Data layer in MVC; reads `reservations` by owner and rejects one in a transaction.
 * Outstanding issues: Rejection needs the owner reservation update rule described in SCHEMA.md;
 *   the deployed rules deny it, so the app reports that rejection is unavailable.
 */
package com.example.legion_events.data

import android.util.Log
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.rejectionNotice
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/**
 * Reads and rejects bookings at the signed-in owner's campsites.
 * @property auth Supplies the owner's identity; callers cannot choose another owner's ID.
 * @property firestore The app's Firestore database.
 * @property now Clock that decides whether a booking is still active; tests supply a fixed clock.
 * @property log Writes diagnostics, such as skipped bookings, to Logcat; tests supply a recorder.
 */
class FirebaseOwnerReservationRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = { Timestamp.now() },
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : OwnerReservationRepository {
    /** Queries by the owner's UID and campsite; entrant profiles are private, so none are read. */
    override suspend fun getCampsiteReservations(campsiteId: String): List<Reservation> {
        requireSingleId(campsiteId, "campsite")
        val ownerId = currentUserId()
        val snapshot = firestore.collection(RESERVATIONS).whereEqualTo("ownerId", ownerId)
            .whereEqualTo("campsiteId", campsiteId).get().await()
        checkAccount(ownerId)
        val source = if (snapshot.metadata?.isFromCache == true) "cache" else "server"
        log("Campsite $campsiteId: ${snapshot.documents.size} booking(s) from $source " +
            snapshot.documents.map { it.id })
        val reservations = snapshot.documents.mapNotNull { document ->
            try {
                document.toReservation()
            } catch (error: IllegalStateException) {
                // A malformed record must not hide the others, but the skip should be traceable.
                log("Skipped reservation ${document.id}: ${error.message}")
                null
            }
        }
        return reservations.filter { it.ownerId == ownerId && it.campsiteId == campsiteId }
    }

    /**
     * Rechecks the owner and status inside a transaction, then writes the rejection and creates
     * the entrant's notice together. `bookedNights` are worker-only under the rules, so they are not touched.
     */
    override suspend fun rejectReservation(reservationId: String, reason: String?) {
        requireSingleId(reservationId, "reservation")
        val text = reason?.trim()?.takeIf { it.isNotEmpty() }
        require(text == null || text.length <= Reservation.MAX_REJECTION_REASON_LENGTH) {
            "Keep the reason under ${Reservation.MAX_REJECTION_REASON_LENGTH} characters."
        }
        val ownerId = currentUserId()
        val reference = firestore.collection(RESERVATIONS).document(reservationId)
        try {
            firestore.runTransaction<Unit> { transaction ->
                val document = transaction.get(reference)
                check(document.exists()) { "This reservation no longer exists." }
                if (document.requiredReservationString("ownerId") != ownerId) {
                    throw SecurityException("This reservation is not at your campsite.")
                }
                val reservation = document.toReservation()
                check(reservation.isActive(now())) { "Only active reservations can be rejected." }
                val campsite = transaction.get(firestore.collection(CAMPSITES).document(reservation.campsiteId))
                transaction.update(reference, mapOf(
                    "status" to Reservation.STATUS_REJECTED,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
                transaction.set(firestore.collection(NOTICES).document(), mapOf(
                    "ownerId" to ownerId,
                    "lotteryId" to reservation.lotteryId,
                    "message" to rejectionNotice(reservation, campsite.getString("name"), text),
                    "recipientIds" to listOf(reservation.userId),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
                Unit
            }.await()
        } catch (error: FirebaseFirestoreException) {
            if (error.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw error
            throw IllegalStateException("Rejecting reservations is not enabled for owners yet.", error)
        }
        checkAccount(ownerId)
    }

    /**
     * Returns the signed-in UID before any database request.
     * @return The owner's UID.
     * @throws IllegalStateException If nobody is signed in.
     */
    private fun currentUserId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /**
     * Prevents a completed request from showing data after the device changes accounts.
     * @param ownerId The UID the request started with.
     * @throws SecurityException If the signed-in account changed.
     */
    private fun checkAccount(ownerId: String) {
        if (auth.currentUser?.uid != ownerId) throw SecurityException("The signed-in account changed.")
    }

    companion object {
        /** Logcat tag for S17 diagnostics. */
        private const val TAG = "OwnerReservations"
        /** Collection holding bookings. */
        private const val RESERVATIONS = "reservations"
        /** Collection holding campsite listings. */
        private const val CAMPSITES = "campsites"
        /** Collection the entrant's inbox reads owner notices from. */
        private const val NOTICES = "ownerNotices"

        /**
         * Rejects blank IDs and paths into other collections.
         * @param id The document ID.
         * @param kind What the ID names, for the error message.
         */
        private fun requireSingleId(id: String, kind: String) {
            require(id.isNotBlank() && '/' !in id) { "A single $kind ID is required." }
        }

        /** Repository for the app's default Firebase project. */
        val instance by lazy {
            FirebaseOwnerReservationRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}
