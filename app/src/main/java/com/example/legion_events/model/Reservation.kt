/*
 * Reservation.kt
 *
 * Purpose: Represent saved bookings and determine active, past, and cancelled stays
 *   (US 01.06.01/02, 02.07.01 to 02.07.03), which ones an owner can reject (US 02.08.01),
 *   and the nights they occupy (US 02.06.01).
 * Role: Model in MVC; keeps reservation fields, the checkout boundary, and booked nights.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
 * @property status Stored reservation status, such as requested, confirmed, cancelled, or rejected.
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

    /** Returns true for confirmed stays at or after checkout, without changing their saved status. */
    fun isPast(at: Timestamp): Boolean = status == STATUS_CONFIRMED && endDate <= at

<<<<<<< HEAD
    /**
     * Returns true for bookings an owner still has to host: requested or confirmed, strictly before
     * checkout. Unlike [isActive], this includes `requested`, the status entrant bookings start in,
     * so owners see new bookings before anything confirms them (S17's Active tab and Reject).
     * @param at The current time.
     * @return Whether the booking belongs in an owner's Active list and can be rejected.
     */
    fun isUpcomingForOwner(at: Timestamp): Boolean =
        (status == STATUS_REQUESTED || status == STATUS_CONFIRMED) && endDate > at

    /**
     * Returns true when the entrant cancelled the booking or the owner rejected it.
     * @return Whether the booking belongs in an owner's Cancelled list.
     */
    fun isCancelled(): Boolean = status == STATUS_CANCELLED || status == STATUS_REJECTED

    /**
     * Lists the nights this stay occupies as UTC `YYYY-MM-DD` keys, matching `availableNights`
     * and `bookedNights` IDs. The checkout night is excluded; a same-day stay occupies one night.
     * @return Night keys in date order.
     */
    fun bookedNights(): List<String> {
        val first = startDate.toDate().time.floorDiv(DAY_MILLIS) * DAY_MILLIS
        val checkout = endDate.toDate().time.floorDiv(DAY_MILLIS) * DAY_MILLIS
        val last = maxOf(checkout, first + DAY_MILLIS)
        val format = nightFormat()
        return generateSequence(first) { it + DAY_MILLIS }.takeWhile { it < last }.map { format.format(it) }.toList()
    }
=======
    /** Allows a new cancellation request for an upcoming or ongoing stay, before checkout. */
    fun canRequestCancellation(at: Timestamp): Boolean = isActive(at) && !cancellationRequested

    /** Confirms that the worker has completed cancellation rather than merely received a request. */
    fun cancellationCompleted(): Boolean = status == STATUS_CANCELLED && !cancellationRequested
>>>>>>> origin

    companion object {
        /** Stored status of a booking an entrant just made, before it is confirmed. */
        const val STATUS_REQUESTED = "requested"
        /** Stored status of an accepted booking that has not been cancelled or rejected. */
        const val STATUS_CONFIRMED = "confirmed"
<<<<<<< HEAD
        /** Stored status of a booking the entrant cancelled. */
        const val STATUS_CANCELLED = "cancelled"
        /** Stored status of a booking the campsite owner rejected (US 02.08.01). */
        const val STATUS_REJECTED = "rejected"
        /** Longest optional reason an owner can give when rejecting, so the notice stays within its limit. */
        const val MAX_REJECTION_REASON_LENGTH = 500
        /** Milliseconds in one UTC day. */
        internal const val DAY_MILLIS = 24 * 60 * 60 * 1000L
=======
        /** Stored only by the trusted worker after releasing the booking's occupied nights. */
        const val STATUS_CANCELLED = "cancelled"
>>>>>>> origin
    }
}

/**
 * Creates the `YYYY-MM-DD` formatter used for night keys.
 * @return A UTC formatter, independent of the device's time zone.
 */
internal fun nightFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Writes the inbox message sent to an entrant whose booking the owner rejected (US 02.08.01).
 * @param reservation The rejected booking; its first night and checkout date are named.
 * @param campsiteName The campsite's current name, or null when unknown.
 * @param reason The owner's optional reason, already trimmed.
 * @return A message within the owner notice limit of [MAX_NOTICE_LENGTH] characters.
 */
fun rejectionNotice(reservation: Reservation, campsiteName: String?, reason: String?): String {
    val days = SimpleDateFormat("MMM d, yyyy", Locale.CANADA).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val place = campsiteName?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
    val stay = "${days.format(reservation.startDate.toDate())} to ${days.format(reservation.endDate.toDate())}"
    val because = reason?.takeIf { it.isNotBlank() }?.let { " Reason: $it" } ?: ""
    return "The owner rejected your reservation$place for $stay.$because".take(MAX_NOTICE_LENGTH)
}
