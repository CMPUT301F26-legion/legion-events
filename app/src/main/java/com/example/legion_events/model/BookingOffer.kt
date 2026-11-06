/* Booking-offer data shared by lottery results and the offer-decline flow. */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * A winner's opportunity to book, stored at `offers/{id}`.
 *
 * The referenced lottery supplies the offered campsite IDs and stay dates. An offer does not
 * reserve a campsite or any nights; availability must be checked when a reservation is made.
 *
 * @property id Firestore document ID, reused when the draw is retried.
 * @property lotteryId ID of the lottery that issued this offer.
 * @property userId Firebase Auth UID of the winning entrant who owns this offer.
 * @property status App-facing offer state: open, used, declined, or expired.
 * @property expiresAt Offer response deadline, or null when no deadline has been set.
 * This is separate from the lottery's booking dates, which describe the stay.
 * @property createdAt Server time when the offer was created, or null before it is available.
 * @property updatedAt Server time when the offer last changed, or null before it is available.
 */
data class BookingOffer(
    val id: String,
    val lotteryId: String,
    val userId: String,
    val status: String = STATUS_OPEN,
    val expiresAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
) {
    /** Offer states exposed to controllers and screens. */
    companion object {
        /** The winner may respond to the offer before its deadline. */
        const val STATUS_OPEN = "open"

        /** The offer has been used to create a reservation. */
        const val STATUS_USED = "used"

        /** The winner has declined the offer (US 01.04.03). */
        const val STATUS_DECLINED = "declined"

        /** The offer's response deadline has passed. */
        const val STATUS_EXPIRED = "expired"
    }
}
