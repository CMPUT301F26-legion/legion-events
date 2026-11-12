/*
 * FirebaseReservationRepository.kt
 *
 * Purpose: Read owned reservations and optional campsite labels from Firestore.
 * Role: Data layer in MVC; enforces UID-scoped, read-only reservation access.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Reads reservations for the authenticated entrant using the existing Firestore rules.
 * @property auth Supplies the entrant's identity; callers cannot choose another user's ID.
 * @property firestore Supplies reservation and optional campsite-label reads.
 */
class FirebaseReservationRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : ReservationRepository {
    /** Queries the current UID only, then attaches each campsite's labels once per request. */
    override suspend fun getMyReservations(): List<ReservationEntry> {
        val userId = currentUserId()
        val documents = firestore.collection("reservations").whereEqualTo("userId", userId).get().await().documents
        checkAccount(userId)
        val reservations = documents.map { it.toOwnedReservation(userId) }
        val labels = reservations.map { it.campsiteId }.distinct().associateWith { campsiteLabels(it) }
        checkAccount(userId)
        return reservations.map { ReservationEntry(it, labels[it.campsiteId]?.first, labels[it.campsiteId]?.second) }
    }

    /**
     * Checks ownership before returning booking details or looking up its campsite.
     * Missing and forbidden documents are both unavailable because the rules also deny missing reads.
     */
    override suspend fun getReservation(reservationId: String): ReservationEntry? {
        require(reservationId.isNotBlank() && '/' !in reservationId) { "A single reservation ID is required." }
        val userId = currentUserId()
        val document = try {
            firestore.collection("reservations").document(reservationId).get().await()
        } catch (error: FirebaseFirestoreException) {
            checkAccount(userId)
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return null
            throw error
        }
        checkAccount(userId)
        if (!document.exists()) return null
        val reservation = document.toOwnedReservation(userId)
        val labels = campsiteLabels(reservation.campsiteId)
        checkAccount(userId)
        return ReservationEntry(reservation, labels?.first, labels?.second)
    }

    /** Returns the signed-in UID before any database request. */
    private fun currentUserId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /** Prevents a completed read from displaying data after the device changes accounts. */
    private fun checkAccount(userId: String) {
        if (auth.currentUser?.uid != userId) throw SecurityException("The signed-in account changed.")
    }

    /** Maps the shared booking fields without turning malformed data into a confirmed stay. */
    private fun DocumentSnapshot.toOwnedReservation(userId: String): Reservation {
        if (requiredString("userId") != userId) throw SecurityException("This reservation is not yours.")
        val start = checkNotNull(get("startDate") as? Timestamp) { "Invalid check-in date." }
        val end = checkNotNull(get("endDate") as? Timestamp) { "Invalid checkout date." }
        check(start < end) { "Checkout must follow check-in." }
        val guests = checkNotNull(get("guestCount") as? Long) { "Invalid guest count." }
        check(guests in 1..Int.MAX_VALUE.toLong()) { "Invalid guest count." }
        val total = checkNotNull(get("totalPrice") as? Long) { "Invalid booking total." }
        check(total >= 0) { "Invalid booking total." }
        val campsiteId = requiredString("campsiteId")
        check('/' !in campsiteId) { "A single campsite document ID is required." }
        return Reservation(
            id = id, lotteryId = requiredString("lotteryId"), offerId = requiredString("offerId"),
            campsiteId = campsiteId, userId = userId, ownerId = requiredString("ownerId"),
            startDate = start, endDate = end, guestCount = guests.toInt(), totalPrice = total,
            rulesAccepted = checkNotNull(get("rulesAccepted") as? Boolean) { "Invalid rules acceptance." },
            status = requiredString("status"),
            cancellationRequested = checkNotNull(get("cancellationRequested") as? Boolean) { "Invalid cancellation flag." },
            createdAt = optionalTimestamp("createdAt"), updatedAt = optionalTimestamp("updatedAt"),
        )
    }

    /** Reads a required nonblank string without silently inventing an ID or status. */
    private fun DocumentSnapshot.requiredString(field: String): String =
        checkNotNull((get(field) as? String)?.takeIf { it.isNotBlank() }) { "Invalid reservation $field." }

    /** Allows pending server timestamps while rejecting values stored with the wrong type. */
    private fun DocumentSnapshot.optionalTimestamp(field: String): Timestamp? {
        val value = get(field)
        check(value == null || value is Timestamp) { "Invalid reservation $field." }
        return value
    }

    /** Listing removal or denied listing access must not hide an entrant's saved booking. */
    private suspend fun campsiteLabels(campsiteId: String): Pair<String?, String?>? = try {
        val document = firestore.collection("campsites").document(campsiteId).get().await()
        (document.get("name") as? String)?.takeIf(String::isNotBlank) to
            (document.get("locationName") as? String)?.takeIf(String::isNotBlank)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: FirebaseFirestoreException) {
        null
    }

    companion object {
        /** Read-only repository for the app's default Firebase project. */
        val instance by lazy { FirebaseReservationRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
