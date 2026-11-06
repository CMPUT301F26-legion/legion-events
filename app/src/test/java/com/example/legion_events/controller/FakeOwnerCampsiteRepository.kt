/*
 * FakeOwnerCampsiteRepository.kt
 *
 * Purpose: In-memory OwnerCampsiteRepository and test lotteries shared by the owner controller tests.
 * Role: Test double; no Firebase access.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.google.firebase.Timestamp
import java.util.Date

/** Day length used to place test lotteries around [NOW]. */
internal const val DAY = 24 * 60 * 60 * 1000L

/** The fixed "current time" in owner controller tests. */
internal const val NOW = 1_800_000_000_000L

/**
 * Builds a lottery whose registration runs between two offsets from [NOW].
 * @param campsites The campsite IDs it covers.
 * @param startDays Days from now when registration opens.
 * @param endDays Days from now when registration closes.
 * @param drawn Whether winners were drawn.
 */
internal fun testLottery(vararg campsites: String, startDays: Int, endDays: Int, drawn: Boolean = false) = Lottery(
    id = "lottery-${campsites.joinToString()}-$startDays", ownerId = "owner", campsiteIds = campsites.toList(),
    registrationStartAt = Timestamp(Date(NOW + startDays * DAY)), registrationEndAt = Timestamp(Date(NOW + endDays * DAY)),
    bookingStartAt = Timestamp(Date(NOW + (endDays + 1) * DAY)), bookingEndAt = Timestamp(Date(NOW + (endDays + 30) * DAY)),
    winnerCount = 5, winnersDrawn = drawn,
)

/** Supplies controlled owner data without connecting to Firebase, and records saves. */
internal class FakeOwnerCampsiteRepository(
    var campsites: suspend () -> List<Campsite> = { emptyList() },
    var lotteries: suspend () -> List<Lottery> = { emptyList() },
    var photos: suspend (List<String>) -> List<Photo> = { emptyList() },
    var save: suspend (Campsite, String?) -> String = { site, _ -> site.id.ifEmpty { "new-site" } },
) : OwnerCampsiteRepository {
    /** Every campsite and photo passed to [saveCampsite]. */
    val saved = mutableListOf<Pair<Campsite, String?>>()
    /** How many times campsites were listed. */
    var listCalls = 0

    override suspend fun getMyCampsites(): List<Campsite> { listCalls++; return campsites() }

    override suspend fun getMyCampsite(campsiteId: String): Campsite? = campsites().find { it.id == campsiteId }

    override suspend fun getMyLotteries(): List<Lottery> = lotteries()

    override suspend fun getPhotos(photoIds: List<String>): List<Photo> = photos(photoIds)

    override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String {
        saved += campsite to coverJpegBase64
        return save(campsite, coverJpegBase64)
    }
}
