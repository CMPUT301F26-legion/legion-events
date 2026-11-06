/*
 * WaitlistRepository.kt
 *
 * Purpose: Contract for joining and leaving lottery waitlists (US 01.01.06, 01.02.01, 01.02.02).
 * Role: Data layer in MVC; controllers use it so they do not depend on Firebase.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Lottery
import com.google.firebase.Timestamp

/**
 * A lottery the signed-in entrant can see, with their own waitlist status.
 * @property lottery The lottery.
 * @property status The entrant's stored status, or null if they never joined.
 * @property campsiteNames Names of the lottery's campsites that are still listed.
 */
data class LotteryEntry(
    val lottery: Lottery,
    val status: String?,
    val campsiteNames: List<String> = emptyList(),
)

/**
 * Whether entrants can join or leave [this] lottery at [now]: it is open, not drawn,
 * and inside its registration period. The Firestore rules check the same thing.
 * @param now The current time.
 * @return True while registration is open.
 */
fun Lottery.isRegistrationOpen(now: Timestamp): Boolean =
    status == "open" && !winnersDrawn && now >= registrationStartAt && now < registrationEndAt

/** Reads and changes the signed-in entrant's lottery waitlist entries. */
interface WaitlistRepository {
    /**
     * Lists the lotteries that offer a campsite, with the entrant's status in each.
     * @param campsiteId The campsite shown on S04.
     * @return Every lottery that includes the campsite, open or not.
     */
    suspend fun getLotteriesForCampsite(campsiteId: String): List<LotteryEntry>

    /**
     * Lists every lottery the entrant has joined, left, won, or lost.
     * @return One entry per lottery, with campsite names for display.
     */
    suspend fun getMyEntries(): List<LotteryEntry>

    /**
     * Adds the entrant to a lottery's waitlist once, in one transaction with its entrant count.
     * @param lotteryId The lottery to join.
     * @throws IllegalStateException If registration is closed, the waitlist is full, or the entrant already joined.
     */
    suspend fun join(lotteryId: String)

    /**
     * Removes the entrant from a waitlist by marking the entry withdrawn, in one transaction with the count.
     * @param lotteryId The lottery to leave.
     * @throws IllegalStateException If registration is closed or the entrant is not on the waitlist.
     */
    suspend fun leave(lotteryId: String)
}
