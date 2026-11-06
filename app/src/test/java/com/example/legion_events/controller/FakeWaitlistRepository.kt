/*
 * FakeWaitlistRepository.kt
 *
 * Purpose: In-memory waitlist used by the waitlist controller tests; no Firebase access.
 * Role: Test double for WaitlistRepository, following the same rules as the Firestore version.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.data.WaitlistRepository
import com.example.legion_events.data.isRegistrationOpen
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp

/**
 * Keeps lotteries and one entrant's statuses in memory.
 * @property lotteries Lotteries by ID; joining and leaving update their entrant counts.
 * @property now Clock used for the registration check.
 */
class FakeWaitlistRepository(
    val lotteries: MutableMap<String, Lottery>,
    private val now: () -> Timestamp,
) : WaitlistRepository {
    /** The entrant's stored status per lottery ID. */
    val statuses = mutableMapOf<String, String>()
    /** When true, every call fails like a lost connection. */
    var offline = false
    /** Number of successful joins. */
    var joins = 0

    override suspend fun getLotteriesForCampsite(campsiteId: String): List<LotteryEntry> {
        if (offline) throw java.io.IOException("Offline")
        return lotteries.values.filter { campsiteId in it.campsiteIds }.map { LotteryEntry(it, statuses[it.id]) }
    }

    override suspend fun getMyEntries(): List<LotteryEntry> {
        if (offline) throw java.io.IOException("Offline")
        return statuses.mapNotNull { (id, status) -> lotteries[id]?.let { LotteryEntry(it, status, listOf("Lake $id")) } }
    }

    override suspend fun join(lotteryId: String) {
        if (offline) throw java.io.IOException("Offline")
        val lottery = checkNotNull(lotteries[lotteryId])
        check(statuses[lotteryId] != WaitlistEntrant.STATUS_JOINED) { "You are already on this waitlist." }
        check(lottery.isRegistrationOpen(now())) { "Registration for this lottery is closed." }
        check(lottery.entrantCount < lottery.waitlistLimit) { "This waitlist is full." }
        statuses[lotteryId] = WaitlistEntrant.STATUS_JOINED
        lotteries[lotteryId] = lottery.copy(entrantCount = lottery.entrantCount + 1)
        joins++
    }

    override suspend fun leave(lotteryId: String) {
        if (offline) throw java.io.IOException("Offline")
        val lottery = checkNotNull(lotteries[lotteryId])
        check(statuses[lotteryId] == WaitlistEntrant.STATUS_JOINED) { "You are not on this waitlist." }
        check(lottery.isRegistrationOpen(now())) { "Registration for this lottery is closed." }
        statuses[lotteryId] = WaitlistEntrant.STATUS_WITHDRAWN
        lotteries[lotteryId] = lottery.copy(entrantCount = lottery.entrantCount - 1)
    }
}
