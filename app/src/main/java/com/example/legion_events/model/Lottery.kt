/*
 * Lottery.kt
 *
 * Purpose: An owner's lottery stored at `lotteries/{id}`.
 * Role: Model in MVC; shared by owner lottery screens, waitlists, and the draw worker.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Largest waitlist the Firestore rules accept; also used when the owner sets no limit (US 02.04.07). */
const val MAX_WAITLIST_LIMIT = 100

/** Most campsites one lottery can offer; the Firestore rules check at most this many owners. */
const val MAX_LOTTERY_CAMPSITES = 8

/**
 * An owner's lottery for one or more campsites, stored at `lotteries/{id}`.
 * Field names match the deployed Firestore security rules exactly.
 * @property id Firestore document ID.
 * @property ownerId ID of the user who created this lottery.
 * @property campsiteIds Campsites offered in this lottery, 1 to [MAX_LOTTERY_CAMPSITES] (US 02.04.01, 02.04.02).
 * @property registrationStartAt When entrants can start joining the waitlist (US 02.04.03).
 * @property registrationEndAt When joining closes and the draw can run (US 02.04.03).
 * @property bookingStartAt When winners can start booking; not before [registrationEndAt] (US 02.04.04).
 * @property bookingEndAt When booking closes (US 02.04.04).
 * @property winnerCount How many entrants are drawn, at most [waitlistLimit] (US 02.04.05).
 * @property waitlistLimit Maximum waitlist size, 1 to [MAX_WAITLIST_LIMIT] (US 02.04.07).
 * @property entrantCount Entrants currently on the waitlist; maintained by joining and leaving.
 * @property status `open` until the draw changes it.
 * @property winnersDrawn Whether winners have been drawn; set once so the draw never runs twice.
 */
data class Lottery(
    val id: String,
    val ownerId: String,
    val campsiteIds: List<String>,
    val registrationStartAt: Timestamp,
    val registrationEndAt: Timestamp,
    val bookingStartAt: Timestamp,
    val bookingEndAt: Timestamp,
    val winnerCount: Int,
    val waitlistLimit: Int = MAX_WAITLIST_LIMIT,
    val entrantCount: Int = 0,
    val status: String = "open",
    val winnersDrawn: Boolean = false,
)
