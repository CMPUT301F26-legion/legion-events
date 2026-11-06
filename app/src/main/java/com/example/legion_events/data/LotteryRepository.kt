/*
 * LotteryRepository.kt
 *
 * Purpose: Contract for creating, editing, and reading lotteries and their waiting lists (US 02.04.01 to 02.04.07, 02.09.01).
 * Role: Data layer in MVC; FirebaseLotteryRepository implements it.
 * Outstanding issues: None.
 */
package com.example.legion_events.data


import com.example.legion_events.model.Lottery
import com.example.legion_events.model.WaitlistEntrant

/** Gives lottery controllers access to lotteries without depending on Firebase. */
interface LotteryRepository {
    /**
     * Saves a new lottery.
     * @param lottery The lottery to save; its id is ignored.
     * @return The new document ID.
     */
    suspend fun createLottery(lottery: Lottery): String

    /**
     * Replaces the editable fields of an existing lottery (US 02.04.06).
     * @param lottery The lottery with its existing id.
     */
    suspend fun updateLottery(lottery: Lottery)

    /**
     * Reads one lottery.
     * @param id The lottery document ID.
     * @throws IllegalStateException If the lottery does not exist.
     */
    suspend fun getLottery(id: String): Lottery

    /**
     * Reads every lottery the signed-in user created, for the owner's Lotteries tab.
     * @return The user's lotteries.
     */
    suspend fun getMyLotteries(): List<Lottery>

    /**
     * Reads every waitlist entry of one lottery, including withdrawn ones (US 02.09.01).
     * Only the lottery's owner may read them.
     * @param lotteryId The lottery document ID.
     * @return The entries in no particular order.
     */
    suspend fun getWaitlist(lotteryId: String): List<WaitlistEntrant>
}
