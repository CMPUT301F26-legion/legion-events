/*
 * Reservation.kt
 *
 * Purpose: Represent saved booking data and determine active stays for US 01.06.01.
 * Role: Model in MVC; keeps reservation fields and the checkout boundary.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * A saved booking at reservations/{id}; campsite names are read separately from the listing.
 * @property id Reservation document ID, also shown as the reservation number.
 * @property lotteryId Lottery that issued the booking opportunity.
 * @property offerId Offer used to make this booking.
 * @property campsiteId Reserved campsite document ID.
 * @property userId Entrant who owns the reservation.
 * @property ownerId User who owns the reserved campsite.
 * @property startDate Check-in instant.
 * @property endDate Checkout instant; the checkout night is excluded from the stay.
 * @property guestCount Number of booked guests.
 * @property totalPrice Saved booking total in integer CAD cents.
 * @property rulesAccepted Whether the entrant accepted the campsite rules.
 * @property status Stored reservation status, such as confirmed, cancelled, or rejected.
 * @property cancellationRequested Whether a cancellation is awaiting processing.
 * @property createdAt Server creation time, or null before it is available.
 * @property updatedAt Server modification time, or null before it is available.
 */
data class Reservation(
    val id: String,
    val lotteryId: String,
    val offerId: String,
    val campsiteId: String,
    val userId: String,
    val ownerId: String,
    val startDate: Timestamp,
    val endDate: Timestamp,
    val guestCount: Int,
    val totalPrice: Long,
    val rulesAccepted: Boolean,
    val status: String,
    val cancellationRequested: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
) {
    /** Returns true for upcoming or ongoing confirmed stays, strictly before checkout. */
    fun isActive(at: Timestamp): Boolean = status == STATUS_CONFIRMED && endDate > at

    companion object {
        /** Stored status of an accepted booking that has not been cancelled or rejected. */
        const val STATUS_CONFIRMED = "confirmed"
    }
}
