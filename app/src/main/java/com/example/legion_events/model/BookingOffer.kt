/* Booking-offer data shared by lottery results and the offer-decline flow. */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * A winner's opportunity to book, stored at `offers/{id}`.
 *
 * The offer records the campsites offered by the draw. The referenced lottery supplies the
 * window during which the winner may submit a booking, while campsite availability supplies
 * the possible stay dates. An offer does not reserve a campsite or any nights.
 *
 * @property id Firestore document ID, reused when the draw is retried.
 * @property lotteryId ID of the lottery that issued this offer.
 * @property userId Firebase Auth UID of the winning entrant who owns this offer.
 * @property status App-facing offer state: open, accepted, used, declined, or expired.
 * @property expiresAt Offer response deadline, or null when no deadline has been set.
 * This is separate from the selected check-in and checkout dates.
 * @property createdAt Server time when the offer was created, or null before it is available.
 * @property updatedAt Server time when the offer last changed, or null before it is available.
 * @property campsiteIds Campsite IDs saved by the draw. Empty for legacy offers that omitted
 * this field; such offers can still be displayed or declined but cannot supply booking options.
 * @property reservationId Request linked when the entrant accepts; retained when the worker confirms.
 * Null before acceptance and for legacy used offers without a link.
 */
data class BookingOffer(
    val id: String,
    val lotteryId: String,
    val userId: String,
    val status: String = STATUS_OPEN,
    val expiresAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val campsiteIds: List<String> = emptyList(),
    val reservationId: String? = null,
) {
    /** Offer states exposed to controllers and screens. */
    companion object {
        /** The winner may respond to the offer before its deadline. */
        const val STATUS_OPEN = "open"

        /** Entrant accepted a request; awaiting the worker, without holding any nights. */
        const val STATUS_ACCEPTED = "accepted"

        /** The worker confirmed a reservation and locked its nights. */
        const val STATUS_USED = "used"

        /** The winner has declined the offer (US 01.04.03). */
        const val STATUS_DECLINED = "declined"

        /** The offer's response deadline has passed. */
        const val STATUS_EXPIRED = "expired"
    }
}
