/*
 * RelatedUsers.kt
 *
 * Purpose: Gather the signed-in user's chat contacts and the entrants booked at their campsites.
 * Role: Data layer in MVC; fetches through existing repositories, then orders with model.listRelatedUsers.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.listRelatedUsers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Reads everyone [userId] has messaged, then entrants of [userId]'s campsites who have not messaged.
 * @param userId Must be the signed-in user; each repository reads only that user's own data.
 * @param messages Supplies past chat contacts.
 * @param campsites Supplies the signed-in owner's campsites.
 * @param reservations Supplies bookings at each of those campsites.
 * @return UIDs with messaged users first and unmessaged entrants at the end.
 * @throws SecurityException If [userId] is not the signed-in user.
 */
suspend fun loadRelatedUsers(userId: String, messages: MessageRepository, campsites: OwnerCampsiteRepository,
    reservations: OwnerReservationRepository): List<String> = coroutineScope {
    val contacts = async { messages.getPastContacts(userId) }
    val bookings = campsites.getMyCampsites().map { site -> async { reservations.getCampsiteReservations(site.id) } }
    listRelatedUsers(userId, contacts.await(), bookings.awaitAll().flatten())
}
