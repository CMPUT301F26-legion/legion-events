/*
 * ReservationRepository.kt
 *
 * Purpose: Define owned reservation reads and cancellation requests.
 * Role: Data contract in MVC; separates reservation controllers from Firebase.
 * Outstanding issues: None.
 */
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

/** Reads owned reservations and submits requests; only the worker changes status and occupied nights. */
interface ReservationRepository {
    /** Returns the entrant's reservations; controllers select the active or other requested period. */
    suspend fun getMyReservations(): List<ReservationEntry>

    /** Returns one owned reservation, or null if missing or forbidden; never returns another entrant's record. */
    suspend fun getReservation(reservationId: String): ReservationEntry?

    /** Requests cancellation once, or returns the already requested/completed booking after a retry. */
    suspend fun requestCancellation(reservationId: String): ReservationEntry
}
