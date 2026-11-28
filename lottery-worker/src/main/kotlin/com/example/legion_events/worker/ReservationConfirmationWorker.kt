/*
 * ReservationConfirmationWorker.kt
 * Purpose: Finalize explicitly accepted booking requests (Person 6, US 01.04.01/02, 02.11.01, 01.05.01).
 * Role: Trusted backend; confirms, locks all nights, consumes the offer, and notifies atomically.
 * Outstanding issues: Confirmation Inbox navigation and booking screens follow in PRs 3 and 4.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore

/** Winning alone never creates a booking. Only a requested record with its matching accept claim is eligible. */
class ReservationConfirmationWorker(private val firestore: Firestore) {
    /** Attempts every pending booking, allowing valid requests to proceed if another record is malformed. */
    fun processPending(): Int = processBookingDocuments(
        firestore.collection("reservations").whereEqualTo("status", "requested").get().get().documents.map { it.reference },
        "booking request", ::confirm,
    )

    /** Rechecks a single request after a write event; duplicates and stale events are harmless. */
    fun processRequest(reservationId: String): Boolean = confirm(firestore.collection("reservations").document(bookingId(reservationId)))

    /** Reads all dependencies and canonical lock documents before any status or occupied-night write. */
    private fun confirm(reference: DocumentReference): Boolean = firestore.runTransaction { transaction ->
        val request = transaction.get(reference).get()
        if (!request.exists() || request.get("status") != "requested") return@runTransaction false
        val uid = request.bookingId("userId")
        val lotteryId = request.bookingId("lotteryId")
        val offerId = request.bookingId("offerId")
        val campsiteId = request.bookingId("campsiteId")
        val ownerId = request.bookingId("ownerId")
        check(offerId == "${lotteryId}_$uid") { "Invalid request offer binding." }
        val offerRef = firestore.collection("offers").document(offerId)
        val offer = transaction.get(offerRef).get()
        // Old standalone requests, declined offers, and obsolete attempts are never auto-approved.
        if (!offer.exists() || offer.get("status") != "pending" || offer.get("response") != "accept" ||
            offer.get("reservationId") != reference.id) return@runTransaction false
        check(offer.bookingId("userId") == uid && offer.bookingId("lotteryId") == lotteryId) { "Offer belongs to another entrant or lottery." }
        val lottery = transaction.get(firestore.collection("lotteries").document(lotteryId)).get()
        val campsite = transaction.get(firestore.collection("campsites").document(campsiteId)).get()
        val profile = transaction.get(firestore.collection("users").document(uid)).get()
        val created = request.bookingTime("createdAt")
        check(offer.bookingTime("respondedAt") == created) { "Offer and request acceptance times disagree." }
        val start = request.bookingTime("startDate")
        val end = request.bookingTime("endDate")
        val nights = bookingNights(start, end)
        val guests = request.get("guestCount") as? Long ?: error("Invalid guest count.")
        val total = request.get("totalPrice") as? Long ?: error("Invalid booking total.")
        val capacity = campsite.get("maxGuests") as? Long ?: 0L
        val listed = campsite.get("availableNights") as? List<*>
        val lockRefs = nights.map { firestore.collection("bookedNights").document("${campsiteId}_$it") }
        val locks = transaction.getAll(*lockRefs.toTypedArray()).get()
        val eligible = lottery.exists() && campsite.exists() && profile.exists() && profile.get("disabled") == false &&
            lottery.get("winnersDrawn") == true && lottery.get("ownerId") == ownerId &&
            campsite.get("active") == true && campsite.get("ownerId") == ownerId &&
            campsiteId in offer.bookingCampsites() && campsiteId in lottery.bookingCampsites() &&
            offer.bookingTime("expiresAt") == lottery.bookingTime("bookingEndAt") &&
            created >= lottery.bookingTime("bookingStartAt") && created < lottery.bookingTime("bookingEndAt") &&
            created < offer.bookingTime("expiresAt") && start.seconds >= created.bookingDaySeconds() &&
            request.get("rulesAccepted") == true && request.get("cancellationRequested") == false &&
            capacity in 1..100 && guests in 1..capacity &&
            (campsite.get("pricePerNight") as? Long)?.let { it in 0..10_000_000L && total == it * nights.size } == true &&
            listed != null && listed.size <= 366 && listed.distinct().size == listed.size &&
            listed.all { date -> date is String && validBookingDate(date) } && listed.containsAll(nights) &&
            locks.none { it.exists() }
        if (!eligible) {
            // Failed availability/validation consumes no offer and creates no locks or success notice.
            // Expiry stays unchanged, so the client can only retry within its original deadline.
            transaction.update(reference, mapOf("status" to "rejected", "updatedAt" to FieldValue.serverTimestamp()))
            transaction.update(offerRef, mapOf("response" to null, "reservationId" to FieldValue.delete(),
                "respondedAt" to FieldValue.delete(), "updatedAt" to FieldValue.serverTimestamp()))
            return@runTransaction false
        }
        lockRefs.zip(nights).forEach { (lock, date) ->
            transaction.create(lock, mapOf("campsiteId" to campsiteId, "reservationId" to reference.id, "date" to date))
        }
        transaction.update(reference, mapOf("status" to "confirmed", "updatedAt" to FieldValue.serverTimestamp()))
        transaction.update(offerRef, mapOf("status" to "used", "updatedAt" to FieldValue.serverTimestamp()))
        transaction.create(firestore.collection("notifications").document("reservation_confirmed_${reference.id}"),
            reservationNotice(uid, lotteryId, reference.id))
        true
    }.get()
}

/** Processes independent records and reports aggregate failures after making all possible progress. */
internal fun processBookingDocuments(
    references: List<DocumentReference>,
    kind: String,
    process: (DocumentReference) -> Boolean,
): Int {
    var completed = 0
    val failures = mutableListOf<Exception>()
    for (reference in references) {
        try { if (process(reference)) completed++ }
        catch (failure: Exception) {
            failures += IllegalStateException("Could not process $kind ${reference.id}.", failure)
        }
    }
    if (failures.isNotEmpty()) throw IllegalStateException("Completed $completed $kind(s); ${failures.size} failed.")
        .also { result -> failures.forEach(result::addSuppressed) }
    return completed
}
