/*
 * RelatedUsersTest.kt
 *
 * Purpose: Verify related users list past contacts first and unmessaged campsite entrants last.
 * Role: Local JVM tests for model.listRelatedUsers and the data-layer loader, using in-memory repositories.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises ordering, de-duplication, and identity checks without Firebase. */
class RelatedUsersTest {
    @Test fun messagedUsersComeFirstThenUnmessagedEntrantsByNewestBooking() {
        val bookings = listOf(booking("r1", "carol", 1), booking("r2", "bob", 2), booking("r3", "dave", 3),
            booking("r4", "dave", 0), booking("r5", "erin", 9, owner = "someoneElse"), booking("r6", "owner", 4))
        assertEquals(listOf("bob", "zoe", "dave", "carol"), listRelatedUsers("owner", listOf("bob", "zoe"), bookings))
        assertEquals(emptyList<String>(), listRelatedUsers("owner", emptyList(), emptyList()))
    }

    @Test fun loaderCombinesContactsWithEntrantsOfEveryOwnedCampsite() = runTest {
        val messages = Contacts(listOf("zoe"))
        val sites = Sites(listOf(site("pine"), site("lake")))
        val bookings = Bookings(mapOf("pine" to listOf(booking("r1", "bob", 1)), "lake" to listOf(booking("r2", "zoe", 5))))
        assertEquals(listOf("zoe", "bob"), loadRelatedUsers("owner", messages, sites, bookings))
        assertEquals(listOf("owner"), messages.asked)
    }

    @Test fun anotherUsersIdIsRejected() = runTest {
        val failure = runCatching { loadRelatedUsers("bob", Contacts(emptyList()), Sites(emptyList()), Bookings(emptyMap())) }
        assertTrue(failure.exceptionOrNull() is SecurityException)
    }

    private fun booking(id: String, entrant: String, at: Long, owner: String = "owner") = Reservation(id, "lottery", "offer",
        "pine", entrant, owner, Timestamp(100, 0), Timestamp(200, 0), 1, 10, true, Reservation.STATUS_CONFIRMED,
        createdAt = Timestamp(at, 0))
    private fun site(id: String) = Campsite(id, "owner", id, "Somewhere", 4, 10)

    /** Returns fixed contacts for the signed-in "owner" and rejects any other identity. */
    private class Contacts(val contacts: List<String>) : MessageRepository {
        val asked = mutableListOf<String>()
        override suspend fun getPastContacts(userId: String): List<String> {
            asked += userId
            if (userId != "owner") throw SecurityException("Not signed in as $userId")
            return contacts
        }
        override fun observeConversations() = error("Unused")
        override suspend fun startConversation(campsiteId: String) = error("Unused")
        override suspend fun startPeerToPeerConversation(otherUserId: String) = error("Unused")
        override suspend fun getConversation(conversationId: String) = error("Unused")
        override fun observeMessages(conversationId: String) = error("Unused")
        override suspend fun send(conversationId: String, messageId: String, text: String) = error("Unused")
    }

    private class Sites(val sites: List<Campsite>) : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = sites
        override suspend fun getMyCampsite(campsiteId: String) = sites.find { it.id == campsiteId }
        override suspend fun getMyLotteries() = emptyList<Lottery>()
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?) = error("Unused")
    }

    private class Bookings(val byCampsite: Map<String, List<Reservation>>) : OwnerReservationRepository {
        override suspend fun getCampsiteReservations(campsiteId: String) = byCampsite[campsiteId].orEmpty()
        override suspend fun acceptReservation(reservationId: String) = error("Unused")
        override suspend fun rejectReservation(reservationId: String, reason: String?) = error("Unused")
    }
}
