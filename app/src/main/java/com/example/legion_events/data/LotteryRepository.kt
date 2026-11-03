package com.example.legion_events.data


import com.example.legion_events.model.Lottery

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
     * Reads every lottery the user created.
     * @param ownerId The owner's user ID.
     */
    suspend fun getLotteriesForOwner(ownerId: String): List<Lottery>
}