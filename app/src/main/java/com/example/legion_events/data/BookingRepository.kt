/*
 * BookingRepository.kt
 * Purpose: Define winner choices, explicit acceptance, and request recovery (Person 6).
 * Role: Data contract in MVC; separates the future booking screens from Firebase.
 * Confirmation is performed by the foreground owner processor or optional trusted backend.
 */
package com.example.legion_events.data

import com.example.legion_events.model.BookingOptions
import com.example.legion_events.model.BookingRequest
import com.example.legion_events.model.Reservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The entrant records acceptance; owner processing atomically confirms, locks nights and consumes the offer. */
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

    /** Watches saved processing outcomes; implementations without streaming support can return one read. */
    fun observeRequestsForOffer(offerId: String): Flow<List<Reservation>> = flow { emit(getRequestsForOffer(offerId)) }
}
