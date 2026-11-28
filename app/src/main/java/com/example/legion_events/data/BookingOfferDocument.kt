/*
 * BookingOfferDocument.kt
 *
 * Purpose: Read the shared offer fields used by offer responses and booking requests.
 * Role: Data-layer mapper; validates ownership before exposing an offer.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.BookingOffer
import com.example.legion_events.model.MAX_LOTTERY_CAMPSITES
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Reads an owned offer without defaulting malformed records to an open booking opportunity.
 * Legacy offers may omit campsite IDs and the reservation link; booking reads must
 * require campsite IDs before offering a reservation.
 * @param userId Authenticated entrant whose offer is being read.
 * @return The validated offer, including its optional booking metadata.
 * @throws SecurityException If the saved offer belongs to another entrant.
 * @throws IllegalStateException If a saved field has an invalid type or value.
 */
internal fun DocumentSnapshot.toOwnedBookingOffer(userId: String): BookingOffer {
    val ownerId = offerString("userId")
    if (ownerId != userId) throw SecurityException("This offer belongs to another user.")
    val status = when (val storedStatus = offerString("status")) {
        "pending" -> when (get("response")) {
            null -> BookingOffer.STATUS_OPEN
            "decline" -> BookingOffer.STATUS_DECLINED
            "accept" -> BookingOffer.STATUS_ACCEPTED
            else -> error("Offer has an invalid response.")
        }
        BookingOffer.STATUS_USED, BookingOffer.STATUS_DECLINED, BookingOffer.STATUS_EXPIRED -> storedStatus
        else -> error("Offer has an invalid status.")
    }
    val campsites = get("campsiteIds")?.let { value ->
        check(value is List<*> && value.size in 1..MAX_LOTTERY_CAMPSITES &&
            value.all { it is String && it.isNotBlank() && '/' !in it } &&
            value.distinct().size == value.size) { "Offer has invalid campsite IDs." }
        value.filterIsInstance<String>()
    }.orEmpty()
    val reservationId = get("reservationId")?.let { value ->
        check(value is String && value.isNotBlank() && '/' !in value) { "Offer has an invalid reservation ID." }
        value
    }
    check(status != BookingOffer.STATUS_ACCEPTED || reservationId != null) { "Accepted offer has no reservation link." }
    return BookingOffer(
        id = id,
        lotteryId = offerString("lotteryId"),
        userId = ownerId,
        status = status,
        expiresAt = offerTimestamp("expiresAt"),
        createdAt = offerTimestamp("createdAt"),
        updatedAt = offerTimestamp("updatedAt"),
        campsiteIds = campsites,
        reservationId = reservationId,
    )
}

/** Reads required offer text without inventing an identifier or state. */
private fun DocumentSnapshot.offerString(field: String): String =
    checkNotNull((get(field) as? String)?.takeIf { it.isNotBlank() }) {
        "Offer has a missing or invalid $field."
    }

/** Accepts an absent optional timestamp while rejecting a value stored with another type. */
private fun DocumentSnapshot.offerTimestamp(field: String): Timestamp? {
    val value = get(field)
    check(value == null || value is Timestamp) { "Offer has an invalid $field." }
    return value
}
