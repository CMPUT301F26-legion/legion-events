/*
 * ReservationCancellationWorker.kt
 *
 * Purpose: Complete requested cancellations and save their confirmation (US 01.07.01/02).
 * Role: Trusted backend worker; releases nights and commits cancellation and its inbox notice together.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import java.util.concurrent.ExecutionException

/** Processes cancellation requests without granting clients permission to change booking status. */
class ReservationCancellationWorker(private val firestore: Firestore) {
    /**
     * Attempts every pending request and reports failures after processing the remaining bookings.
     * @return Number of cancellations committed by this run; retries and concurrent runs count once.
     */
    fun processPending(): Int {
        val pending = firestore.collection("reservations").whereEqualTo("cancellationRequested", true).get().get().documents
        val failures = mutableListOf<Exception>()
        var completed = 0
        for (reservation in pending) {
            try {
                if (cancel(reservation.reference)) completed++
            } catch (failure: ExecutionException) {
                failures += IllegalStateException("Cancellation failed for ${reservation.id}.", failure.cause ?: failure)
            } catch (failure: RuntimeException) {
                failures += IllegalStateException("Cancellation failed for ${reservation.id}.", failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Completed $completed cancellation(s); ${failures.size} failed.")
                .also { summary -> failures.forEach(summary::addSuppressed) }
        }
        return completed
    }

    /** Rechecks the request and releases only nights still assigned to this booking in one transaction. */
    private fun cancel(reference: DocumentReference): Boolean = firestore.runTransaction { transaction ->
        val reservation = transaction.get(reference).get()
        if (!reservation.exists() || reservation.getString("status") != "confirmed" ||
            reservation.getBoolean("cancellationRequested") != true) return@runTransaction false
        val userId = checkNotNull(reservation.getString("userId"))
        val campsiteId = checkNotNull(reservation.getString("campsiteId"))
        val lotteryId = checkNotNull(reservation.getString("lotteryId"))
        check(listOf(userId, campsiteId, lotteryId).all { it.isNotBlank() && '/' !in it }) { "Invalid booking references." }

        // Query ownership instead of calculating dates: never delete a night rebooked by someone else.
        val nights = transaction.get(firestore.collection("bookedNights")
            .whereEqualTo("reservationId", reference.id).limit(500)).get().documents
        check(nights.size <= 498) { "Too many nights for an atomic cancellation." }
        check(nights.all { it.getString("campsiteId") == campsiteId }) { "Night belongs to a different campsite." }

        nights.forEach { transaction.delete(it.reference) }
        transaction.update(reference, mapOf("status" to "cancelled", "cancellationRequested" to false,
            "updatedAt" to FieldValue.serverTimestamp()))
        transaction.create(firestore.collection("notifications").document("reservation_cancelled_${reference.id}"), mapOf(
            "toUserId" to userId, "fromUserId" to null, "lotteryId" to lotteryId,
            "reservationId" to reference.id, "type" to "reservation_cancelled",
            "title" to "Reservation cancelled", "body" to "Your reservation has been cancelled and its dates released.",
            "read" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
        ))
        true
    }.get()
}
