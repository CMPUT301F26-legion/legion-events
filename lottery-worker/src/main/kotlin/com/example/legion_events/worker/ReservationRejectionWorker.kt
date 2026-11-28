/*
 * ReservationRejectionWorker.kt
 * Purpose: Release owner-rejected stays and allow a fresh attempt after pending-request rejection.
 * Role: Trusted backend; deletes only matching locks and reopens only the rejected pending claim.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore

/** Owner rejection notices already come from the owner action; this processor creates no duplicate notice. */
class ReservationRejectionWorker(private val firestore: Firestore) {
    /** Repairs every unprocessed rejection, including an owner rejection racing confirmation. */
    fun processPending(): Int = processBookingDocuments(
        firestore.collection("reservations").whereEqualTo("status", "rejected").get().get().documents.map { it.reference },
        "rejection release", ::release,
    )

    /** Rechecks a single reservation following a write event. */
    fun processRequest(reservationId: String): Boolean = release(firestore.collection("reservations").document(bookingId(reservationId)))

    /** Atomically releases matching locks, clears a pending claim if present, and records completion once. */
    private fun release(reference: DocumentReference): Boolean = firestore.runTransaction { transaction ->
        val reservation = transaction.get(reference).get()
        if (!reservation.exists() || reservation.get("status") != "rejected") return@runTransaction false
        val marker = firestore.collection("reservationReleases").document(reference.id)
        if (transaction.get(marker).get().exists()) return@runTransaction false
        val campsiteId = reservation.bookingId("campsiteId")
        val userId = reservation.bookingId("userId")
        val lotteryId = reservation.bookingId("lotteryId")
        val offerId = reservation.bookingId("offerId")
        check(offerId == "${lotteryId}_$userId") { "Invalid rejected reservation binding." }
        val offerRef = firestore.collection("offers").document(offerId)
        val offer = transaction.get(offerRef).get()
        val nights = transaction.get(firestore.collection("bookedNights").whereEqualTo("reservationId", reference.id)
            .limit(498)).get().documents
        check(nights.size <= 497) { "Too many nights for an atomic rejection release." }
        check(nights.all { it.get("campsiteId") == campsiteId }) { "Rejected lock belongs to a different campsite." }
        val pendingClaim = offer.exists() && offer.get("status") == "pending" && offer.get("response") == "accept" &&
            offer.get("reservationId") == reference.id
        if (pendingClaim) {
            check(offer.get("userId") == userId && offer.get("lotteryId") == lotteryId) { "Rejected claim has invalid ownership." }
        }
        nights.forEach { transaction.delete(it.reference) }
        if (pendingClaim) transaction.update(offerRef, mapOf("response" to null,
            "reservationId" to FieldValue.delete(), "respondedAt" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp()))
        if (reservation.get("cancellationRequested") == true) transaction.update(reference, mapOf(
            "cancellationRequested" to false, "updatedAt" to FieldValue.serverTimestamp()))
        transaction.create(marker, mapOf("reservationId" to reference.id, "processedAt" to FieldValue.serverTimestamp()))
        true
    }.get()
}
