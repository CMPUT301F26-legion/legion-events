/*
 * RatingRepository.kt
 *
 * Purpose: Define completed-stay rating reads and saves for S10c.
 * Role: Data contract in MVC; separates the rating controller from Firebase access.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Rating

/**
 * An eligible owned stay and the entrant's existing rating for its campsite, if any.
 * @property stay Saved booking with optional current campsite labels.
 * @property rating Existing score shared by all of this entrant's stays at the campsite.
 */
data class RatingDetails(val stay: ReservationEntry, val rating: Rating?)

/** Reads and saves one campsite rating after checking the authenticated entrant's completed stay. */
interface RatingRepository {
    /** Returns the owned eligible stay and existing score, or null when rating is unavailable. */
    suspend fun getForStay(reservationId: String): RatingDetails?

    /** Rechecks eligibility and creates or updates the current UID's 1–5 score without resetting creation time. */
    suspend fun saveForStay(reservationId: String, score: Int): Rating
}
