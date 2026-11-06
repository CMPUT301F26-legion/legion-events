/* Repository contract for reading and declining the signed-in entrant's booking offers. */
package com.example.legion_events.data

import com.example.legion_events.model.BookingOffer

/**
 * Gives offer controllers access to the signed-in user's offers without depending on Firebase.
 *
 * The draw worker creates offers when it commits lottery results. Implementations of this
 * interface must enforce ownership in the data layer and Firestore rules, using the current
 * Firebase Auth user rather than accepting a user ID supplied by a view.
 */
interface OfferRepository {
    /**
     * Reads one offer belonging to the signed-in user.
     * @param offerId Firestore document ID of the offer.
     * @return The saved offer, or null if it no longer exists.
     * @throws IllegalStateException If no user is signed in.
     * @throws IllegalArgumentException If offerId is blank or contains a document path.
     * @throws SecurityException If the offer belongs to another user or Firestore denies access.
     */
    suspend fun getOffer(offerId: String): BookingOffer?

    /**
     * Reads all offers belonging to the signed-in user, including offers that are no longer open.
     * @return The user's offers, or an empty list if they have none.
     * @throws IllegalStateException If no user is signed in.
     * @throws SecurityException If Firestore denies access.
     */
    suspend fun getOffersForCurrentUser(): List<BookingOffer>

    /**
     * Declines the signed-in user's open offer (US 01.04.03).
     *
     * Implementations must check ownership, status, and response deadline atomically with
     * the update. Only the decline response and server-managed timestamps may change. An already declined
     * offer is returned unchanged so a retry after a lost response is safe. Used or expired
     * offers, including open offers whose deadline has passed, cannot be declined.
     *
     * @param offerId Firestore document ID of the offer to decline.
     * @return The saved declined offer.
     * @throws IllegalStateException If no user is signed in, the offer is missing, or it can no
     * longer be declined.
     * @throws IllegalArgumentException If offerId is blank or contains a document path.
     * @throws SecurityException If the offer belongs to another user or Firestore denies access.
     */
    suspend fun declineOffer(offerId: String): BookingOffer
}
