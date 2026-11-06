/*
 * OwnerNoticeRepository.kt
 *
 * Purpose: Contract for owner notices to lottery waitlists (US 02.10.01, 02.10.02).
 * Role: Data layer in MVC; S18 sends and lists notices, and the S07 inbox receives them.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.OwnerNotice
import kotlinx.coroutines.flow.Flow

/** Sends owner notices and reads them for the sender or the recipients. */
interface OwnerNoticeRepository {
    /**
     * Sends a notice to everyone currently on a lottery's waitlist. This is the
     * sendNotification() call the work split assigns to Person 5.
     * @param lotteryId A lottery the signed-in user owns.
     * @param message The notice text.
     * @return How many entrants received it.
     * @throws IllegalStateException If nobody is on the waitlist.
     */
    suspend fun sendNotification(lotteryId: String, message: String): Int

    /**
     * Lists the notices the signed-in owner has sent, newest first.
     * @return Every notice this owner sent.
     */
    suspend fun getSentNotices(): List<OwnerNotice>

    /**
     * Observes notices sent to an entrant, newest first. Empty while the entrant has
     * notifications turned off on S11 (US 01.10.01).
     * @param userId The signed-in entrant.
     * @return The entrant's received notices, updated as new ones arrive.
     */
    fun observeReceived(userId: String): Flow<List<OwnerNotice>>
}
