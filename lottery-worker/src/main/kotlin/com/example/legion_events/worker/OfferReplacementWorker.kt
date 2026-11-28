/*
 * OfferReplacementWorker.kt
 * Purpose: Replace only a declining lottery winner without rerunning the initial draw.
 * Role: Trusted backend; atomically selects one remaining entrant and publishes their new offer.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore

/** Each declined offer has one immutable processing marker, including when nobody remains eligible. */
class OfferReplacementWorker(
    private val firestore: Firestore,
    private val now: () -> Timestamp = { Timestamp.now() },
) {
    /** Tries every recorded decline and reports errors after allowing other lotteries to progress. */
    fun processPending(): Int = processBookingDocuments(
        firestore.collection("offers").whereEqualTo("response", "decline").get().get().documents.map { it.reference },
        "decline replacement", ::replace,
    )

    /** Handles a changed offer from an event; expired and previously processed declines do not redraw. */
    fun processOffer(offerId: String): Boolean = replace(firestore.collection("offers").document(bookingId(offerId)))

    /** Preserves original winners and manifests; candidate entry and offer creation serialize competing declines. */
    private fun replace(reference: DocumentReference): Boolean = firestore.runTransaction { transaction ->
        val offer = transaction.get(reference).get()
        if (!offer.exists() || offer.get("status") != "pending" || offer.get("response") != "decline") return@runTransaction false
        val markerRef = firestore.collection("offerReplacements").document(reference.id)
        if (transaction.get(markerRef).get().exists()) return@runTransaction false
        val lotteryId = offer.bookingId("lotteryId")
        val decliner = offer.bookingId("userId")
        check(reference.id == "${lotteryId}_$decliner" && offer.get("reservationId") == null) { "Invalid declined offer binding." }
        val lottery = transaction.get(firestore.collection("lotteries").document(lotteryId)).get()
        check(lottery.exists() && lottery.get("winnersDrawn") == true) { "The initial draw has not completed." }
        val expires = offer.bookingTime("expiresAt")
        check(expires == lottery.bookingTime("bookingEndAt") && offer.bookingCampsites() == lottery.bookingCampsites()) {
            "Declined offer disagrees with the original lottery."
        }
        // Late decline processing cannot extend the original deadline or issue an expired invitation.
        if (now() >= expires) {
            transaction.create(markerRef, marker(lotteryId, reference.id, null))
            return@runTransaction false
        }
        val entries = transaction.get(firestore.collection("waitlist").whereEqualTo("lotteryId", lotteryId).limit(101)).get().documents
        val offers = transaction.get(firestore.collection("offers").whereEqualTo("lotteryId", lotteryId).limit(101)).get().documents
        val confirmed = transaction.get(firestore.collection("reservations").whereEqualTo("lotteryId", lotteryId)
            .whereEqualTo("status", "confirmed").limit(101)).get().documents
        check(entries.size <= 100 && offers.size <= 100 && confirmed.size <= 100) { "Lottery exceeds supported entrant limits." }
        val manifest = transaction.get(firestore.collection("lotteryDraws").document(lotteryId)).get()
        val originalEntrants = if (manifest.exists()) {
            (manifest.get("entrantIds") as? List<*>)?.map(::bookingId)?.toSet() ?: error("Invalid initial draw manifest.")
        } else null // Cloud draws predate the Android-only manifest.
        val originalWinners = if (manifest.exists()) {
            (manifest.get("winnerIds") as? List<*>)?.map(::bookingId)?.toSet() ?: error("Invalid initial draw winners.")
        } else emptySet()
        val byUser = entries.groupBy { it.bookingId("userId") }
        val previouslySelected = offers.map { it.bookingId("userId") }.toSet() + originalWinners +
            confirmed.map { it.bookingId("userId") } + byUser.filterValues { group -> group.any { it.get("status") == "won" } }.keys
        val candidates = byUser.filter { (uid, records) ->
            uid != decliner && uid !in previouslySelected && (originalEntrants == null || uid in originalEntrants) &&
                records.all { it.get("status") == "lost" }
        }.keys.sorted()
        val profiles = if (candidates.isEmpty()) emptyList() else transaction.getAll(*candidates.map {
            firestore.collection("users").document(it)
        }.toTypedArray()).get()
        val chosen = profiles.filter { it.exists() && it.get("disabled") == false }.shuffled().firstOrNull()?.id
        // A fresh event ID keeps the original loss as history and cannot collide with its notification.
        val eventId = "lottery_replacement_${reference.id}"
        if (chosen != null) {
            val offerId = "${lotteryId}_$chosen"
            val replacementRef = firestore.collection("offers").document(offerId)
            check(!transaction.get(replacementRef).get().exists()) { "Replacement already has an offer." }
            byUser.getValue(chosen).forEach { entry ->
                transaction.update(entry.reference, mapOf("status" to "won", "updatedAt" to FieldValue.serverTimestamp()))
            }
            transaction.create(replacementRef, mapOf("lotteryId" to lotteryId, "userId" to chosen,
                "campsiteIds" to offer.bookingCampsites(), "status" to "pending", "response" to null,
                "expiresAt" to expires, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
            val result = mapOf("type" to "lottery_won", "toUserId" to chosen, "lotteryId" to lotteryId,
                "offerId" to offerId, "createdAt" to FieldValue.serverTimestamp())
            transaction.create(firestore.collection("notifications").document(eventId), result)
            transaction.create(firestore.collection("lotteryNotificationOutbox").document(eventId), result +
                mapOf("deliveryStatus" to "pending", "updatedAt" to FieldValue.serverTimestamp()))
        }
        transaction.create(markerRef, marker(lotteryId, reference.id, chosen))
        chosen != null
    }.get()

    /** Records the decision once; a null winner also prevents repeated draws of an exhausted pool. */
    private fun marker(lotteryId: String, declinedOfferId: String, uid: String?): Map<String, Any?> = mapOf(
        "lotteryId" to lotteryId, "declinedOfferId" to declinedOfferId,
        "replacementUserId" to uid, "replacementOfferId" to uid?.let { "${lotteryId}_$it" },
        "processedAt" to FieldValue.serverTimestamp(),
    )
}
