/*
 * BookingRepository.kt
 * Purpose: Define winner choices, explicit acceptance, and request recovery (Person 6).
 * Role: Data contract in MVC; separates the future booking screens from Firebase.
 * Outstanding issues: Screens and the confirming worker are delivered in later PRs.
 */
package com.example.legion_events.data

import com.example.legion_events.model.BookingOptions
import com.example.legion_events.model.BookingRequest
import com.example.legion_events.model.Reservation

/** Only the trusted worker confirms a request, locks nights, and consumes the offer together. */
interface BookingRepository {
    /** Reads current offered campsites, policies, and provisional availability from the server. */
    suspend fun getBookingOptions(offerId: String): BookingOptions

    /**
     * Atomically claims an open offer and saves an explicitly accepted, immutable request.
     * Retrying the same ID and details returns its saved state without creating another request.
     * No campsite nights are held by this operation.
     */
    suspend fun acceptOffer(request: BookingRequest): Reservation

    /** Recovers this entrant's attempts for an offer, including rejected attempts and confirmations. */
    suspend fun getRequestsForOffer(offerId: String): List<Reservation>
}
