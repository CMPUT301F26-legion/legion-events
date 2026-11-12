package com.example.legion_events.data

import com.example.legion_events.model.Reservation

/**
 * A reservation with optional current listing labels for display.
 * @property reservation The saved booking; its dates, guest count, and price are authoritative.
 * @property campsiteName Current campsite name, or null when the listing cannot be read.
 * @property locationName Current campsite area, or null when the listing cannot be read.
 */
data class ReservationEntry(
    val reservation: Reservation,
    val campsiteName: String? = null,
    val locationName: String? = null,
)

/** Reads reservations owned by the signed-in entrant without changing bookings or occupied nights. */
interface ReservationRepository {
    /** Returns the entrant's reservations; controllers select the active or other requested period. */
    suspend fun getMyReservations(): List<ReservationEntry>

    /** Returns one owned reservation, or null if missing; refuses access to another entrant's record. */
    suspend fun getReservation(reservationId: String): ReservationEntry?
}
