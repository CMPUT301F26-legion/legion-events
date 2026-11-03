package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * An owner's lottery for one or more campsites, stored at `lotteries/{id}`.
 * @property id Firestore document ID.
 * @property ownerId ID of the user who created this lottery.
 * @property title Name shown to entrants.
 * @property campsiteIds Campsites offered in this lottery; at least one (US 02.04.01, 02.04.02).
 * @property registrationStart When entrants can start joining the waitlist (US 02.04.03).
 * @property registrationEnd When joining closes and the draw can run (US 02.04.03).
 * @property bookingStart First night winners can book, in YYYY-MM-DD format (US 02.04.04).
 * @property bookingEnd Last night winners can book, in YYYY-MM-DD format (US 02.04.04).
 * @property numWinners How many entrants are drawn (US 02.04.05).
 * @property waitlistCapacity Maximum waitlist size, or null for no limit (US 02.04.07).
 * @property drawn Whether winners have been drawn; set once by the draw so it never runs twice.
 */
data class Lottery(
    val id: String,
    val ownerId: String,
    val title: String,
    val campsiteIds: List<String>,
    val registrationStart: Timestamp,
    val registrationEnd: Timestamp,
    val bookingStart: String,
    val bookingEnd: String,
    val numWinners: Int,
    val waitlistCapacity: Int? = null,
    val drawn: Boolean = false,
)