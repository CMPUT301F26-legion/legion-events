/*
 * ReservationDocument.kt
 *
 * Purpose: Map `reservations/{id}` documents to Reservation for the entrant and owner repositories.
 * Role: Data layer in MVC; one strict reader so both sides interpret bookings the same way.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Maps the shared booking fields without turning malformed data into a confirmed stay.
 * Callers check who may read the record before calling this.
 * @return The saved booking.
 * @throws IllegalStateException If a field is missing or has the wrong type or range.
 */
internal fun DocumentSnapshot.toReservation(): Reservation {
    val start = checkNotNull(get("startDate") as? Timestamp) { "Invalid check-in date." }
    val end = checkNotNull(get("endDate") as? Timestamp) { "Invalid checkout date." }
    check(start < end) { "Checkout must follow check-in." }
    val guests = checkNotNull(get("guestCount") as? Long) { "Invalid guest count." }
    check(guests in 1..Int.MAX_VALUE.toLong()) { "Invalid guest count." }
    val total = checkNotNull(get("totalPrice") as? Long) { "Invalid booking total." }
    check(total >= 0) { "Invalid booking total." }
    val campsiteId = requiredReservationString("campsiteId")
    check('/' !in campsiteId) { "A single campsite document ID is required." }
    return Reservation(
        id = id, lotteryId = requiredReservationString("lotteryId"), offerId = requiredReservationString("offerId"),
        campsiteId = campsiteId, userId = requiredReservationString("userId"), ownerId = requiredReservationString("ownerId"),
        startDate = start, endDate = end, guestCount = guests.toInt(), totalPrice = total,
        rulesAccepted = checkNotNull(get("rulesAccepted") as? Boolean) { "Invalid rules acceptance." },
        status = requiredReservationString("status"),
        cancellationRequested = checkNotNull(get("cancellationRequested") as? Boolean) { "Invalid cancellation flag." },
        createdAt = optionalTimestamp("createdAt"), updatedAt = optionalTimestamp("updatedAt"),
    )
}

/**
 * Reads a required nonblank string without silently inventing an ID or status.
 * @param field The reservation field name.
 * @return The stored string.
 * @throws IllegalStateException If the field is missing, blank, or not a string.
 */
internal fun DocumentSnapshot.requiredReservationString(field: String): String =
    checkNotNull((get(field) as? String)?.takeIf { it.isNotBlank() }) { "Invalid reservation $field." }

/**
 * Allows pending server timestamps while rejecting values stored with the wrong type.
 * @param field The reservation field name.
 * @return The timestamp, or null while the server has not set it.
 * @throws IllegalStateException If the field holds another type.
 */
private fun DocumentSnapshot.optionalTimestamp(field: String): Timestamp? {
    val value = get(field)
    check(value == null || value is Timestamp) { "Invalid reservation $field." }
    return value
}
