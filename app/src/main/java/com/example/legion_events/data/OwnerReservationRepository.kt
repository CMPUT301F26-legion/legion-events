/*
 * OwnerReservationRepository.kt
 *
 * Purpose: Contract for reading a campsite owner's bookings and accepting or rejecting one
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
     * Accepts a requested booking at one of the owner's campsites (`requested -> confirmed`) and
     * sends the entrant a notice in the same write. Nights are reserved by the trusted worker, not the app.
     * @param reservationId A single reservation document ID.
     * @throws IllegalStateException With a message for the owner when the booking can no longer be accepted.
     * @throws SecurityException If the booking belongs to another owner or the signed-in account changed.
     */
    suspend fun acceptReservation(reservationId: String)

    /**
     * Rejects an upcoming requested or confirmed booking at one of the owner's campsites and sends the entrant a notice
     * in the same write. Occupied nights are released by the trusted worker, not the app.
     * @param reservationId A single reservation document ID.
     * @param reason The owner's optional reason, at most [Reservation.MAX_REJECTION_REASON_LENGTH] characters.
     * @throws IllegalStateException With a message for the owner when the booking can no longer be rejected.
     * @throws SecurityException If the booking belongs to another owner or the signed-in account changed.
     */
    suspend fun rejectReservation(reservationId: String, reason: String?)
}
