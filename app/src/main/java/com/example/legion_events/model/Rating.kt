/*
 * Rating.kt
 *
 * Purpose: Keep one 1–5 score per entrant and campsite (US 01.13.01).
 * Role: Model in MVC; represents the ratings database contract without Firebase operations.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * A saved rating at ratings/{campsiteId}_{userId}; repeat stays share the same rating.
 * @property campsiteId Rated campsite document ID.
 * @property userId Firebase UID of the entrant who submitted it.
 * @property score Whole-number score from one to five.
 * @property createdAt Original server creation time, or null while pending.
 * @property updatedAt Server time of the last score change, or null while pending.
 */
data class Rating(
    val campsiteId: String,
    val userId: String,
    val score: Int,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
) {
    init { require(score in 1..5) { "A rating must be between 1 and 5." } }
}
