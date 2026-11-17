/*
 * OwnerReservationRepository.kt
 *
 * Purpose: Contract for reading a campsite owner's bookings and rejecting one
 *   (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: Data layer in MVC; FirebaseOwnerReservationRepository implements it for S17.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Reservation

/** Gives S17 the signed-in owner's bookings without depending on Firebase. */
interface OwnerReservationRepository {
    /**
     * Reads every booking at one campsite the signed-in user owns, in any status.
     * @param campsiteId A single campsite document ID.
     * @return The bookings; malformed records are left out.
     */
    suspend fun getCampsiteReservations(campsiteId: String): List<Reservation>

    /**
     * Rejects an active booking at one of the owner's campsites and sends the entrant a notice
     * in the same write. Occupied nights are released by the trusted worker, not the app.
     * @param reservationId A single reservation document ID.
     * @param reason The owner's optional reason, at most [Reservation.MAX_REJECTION_REASON_LENGTH] characters.
     * @throws IllegalStateException With a message for the owner when the booking can no longer be rejected.
     * @throws SecurityException If the booking belongs to another owner.
     */
    suspend fun rejectReservation(reservationId: String, reason: String?)
}
