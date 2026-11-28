/* Foreground owner-side booking automation using the ordinary authenticated Android SDK. */
package com.example.legion_events.data

import com.example.legion_events.model.bookingNightKeys
import com.example.legion_events.model.isBookingNightKey
import com.example.legion_events.model.requireBookingId
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await

/** Independent failures never prevent another campsite's requests from progressing. */
data class LocalBookingResult(val completed: Int, val failures: List<Throwable> = emptyList())

/** Checks only the signed-in owner's records; never needs service-account credentials. */
interface LocalBookingRepository {
    suspend fun process(ownerId: String): LocalBookingResult

    /**
     * Emits whenever one of the owner's reservations changes, so new requests and cancellation
     * requests are processed without waiting for the next poll. The first emission is the current state.
     */
    fun reservationChanges(ownerId: String): Flow<Unit> = emptyFlow()
}

/**
 * Server queries discover work; transactions re-read every dependency before writing.
 * Canonical night documents serialize competing bookings, including multiple owner devices.
 * Owners are trusted processors for their own campsites, as with foreground lottery draws.
 */
class FirebaseLocalBookingRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = Timestamp::now,
) : LocalBookingRepository {
    override suspend fun process(ownerId: String): LocalBookingResult {
        checkAccount(ownerId)
        // Every reservation and decline belongs to an owned lottery, and lotteries are never deleted,
        // so accounts that own none skip the other reads.
        val lotteries = firestore.collection("lotteries").whereEqualTo("ownerId", ownerId)
            .get(Source.SERVER).await().documents
        checkAccount(ownerId)
        if (lotteries.isEmpty()) return LocalBookingResult(0)
        val requests = firestore.collection("reservations").whereEqualTo("ownerId", ownerId)
            .get(Source.SERVER).await().documents
        checkAccount(ownerId)
        var completed = 0
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(label: String, action: suspend () -> Boolean) {
            try { if (action()) completed++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failures += IllegalStateException("Could not process $label.", error) }
        }
        // Free rejected/cancelled dates before considering new acceptances.
        requests.filter { it.get("status") == "rejected" ||
            (it.get("status") == "confirmed" && it.get("cancellationRequested") == true) }
            .forEach { attempt("reservation ${it.id}") { release(it.id, ownerId) } }
        requests.filter { it.get("status") == "requested" }
            .sortedWith(compareBy<DocumentSnapshot> { it.get("createdAt") as? Timestamp }.thenBy { it.id })
            .forEach { attempt("reservation ${it.id}") { confirm(it.id, ownerId) } }
        attempt("owned lottery declines") {
          lotteries.filter { it.get("winnersDrawn") == true }.forEach { lottery ->
            attempt("declines for lottery ${lottery.id}") {
                val offers = firestore.collection("offers").whereEqualTo("lotteryId", lottery.id)
                    .get(Source.SERVER).await().documents
                checkAccount(ownerId)
                offers.filter { it.get("status") == "pending" && it.get("response") == "decline" }
                    .forEach { offer -> attempt("declined offer ${offer.id}") { replace(offer.id, ownerId) } }
                false
            }
          }
          false
        }
        return LocalBookingResult(completed, failures)
    }

    /** Listens to the owner's reservations; only changed documents are read after the first snapshot. */
    override fun reservationChanges(ownerId: String): Flow<Unit> = callbackFlow {
        val registration = firestore.collection("reservations").whereEqualTo("ownerId", ownerId)
            .addSnapshotListener { _, error -> if (error != null) close(error) else trySend(Unit) }
        awaitClose { registration.remove() }
    }

    /** Explicit acceptance is the approval; the owner never needs to press Accept. */
    internal suspend fun confirm(id: String, ownerId: String): Boolean {
        requireBookingId(id)
        checkAccount(ownerId)
        val initial = ref("reservations", id).get(Source.SERVER).await()
        checkAccount(ownerId)
        if (!initial.exists() || initial.get("status") != "requested") return false
        check(initial.get("ownerId") == ownerId) { "Reservation belongs to another owner." }
        val activeEntrant = entrantActive(initial.idField("lotteryId"), initial.idField("userId"), ownerId)
        return firestore.runTransaction { tx ->
            checkAccount(ownerId)
            val ref = ref("reservations", id)
            val request = tx.get(ref)
            if (!request.exists() || request.get("status") != "requested") return@runTransaction false
            check(request.get("ownerId") == ownerId) { "Reservation belongs to another owner." }
            val uid = request.idField("userId")
            val lotteryId = request.idField("lotteryId")
            val offerId = request.idField("offerId")
            val siteId = request.idField("campsiteId")
            check(offerId == "${lotteryId}_$uid") { "Invalid offer binding." }
            val offerRef = ref("offers", offerId)
            val offer = tx.get(offerRef)
            if (!offer.exists() || offer.get("status") != "pending" || offer.get("response") != "accept" ||
                offer.get("reservationId") != id) return@runTransaction false
            check(offer.get("userId") == uid && offer.get("lotteryId") == lotteryId) { "Invalid acceptance." }
            val lottery = tx.get(ref("lotteries", lotteryId))
            val site = tx.get(ref("campsites", siteId))
            val created = request.time("createdAt")
            check(created == offer.time("respondedAt")) { "Acceptance timestamps disagree." }
            val start = request.time("startDate")
            val nights = bookingNightKeys(start, request.time("endDate"))
            val locks = nights.map { ref("bookedNights", "${siteId}_$it") }
            val occupied = locks.map(tx::get)
            val guests = request.get("guestCount") as? Long ?: error("Invalid guest count.")
            val total = request.get("totalPrice") as? Long ?: error("Invalid total.")
            val capacity = site.get("maxGuests") as? Long ?: 0L
            val price = site.get("pricePerNight") as? Long
            val listed = site.get("availableNights") as? List<*>
            val eligible = activeEntrant && lottery.exists() && site.exists() && lottery.get("ownerId") == ownerId &&
                site.get("ownerId") == ownerId && site.get("active") == true && lottery.get("winnersDrawn") == true &&
                siteId in offer.campsites() && siteId in lottery.campsites() &&
                offer.time("expiresAt") == lottery.time("bookingEndAt") &&
                created >= lottery.time("bookingStartAt") && created < lottery.time("bookingEndAt") &&
                created < offer.time("expiresAt") && start.seconds >= created.seconds.floorDiv(86_400L) * 86_400L &&
                request.get("rulesAccepted") == true && request.get("cancellationRequested") == false &&
                capacity in 1..100 && guests in 1..capacity && price != null && price in 0..10_000_000L &&
                total == price * nights.size && listed != null && listed.size <= 366 &&
                listed.distinct().size == listed.size && listed.all { it is String && isBookingNightKey(it) } &&
                listed.containsAll(nights) && occupied.none { it.exists() }
            checkAccount(ownerId)
            if (!eligible) {
                tx.update(ref, mapOf("status" to "rejected", "updatedAt" to stamp()))
                reopen(tx, offerRef)
                return@runTransaction true
            }
            locks.zip(nights).forEach { (lock, date) ->
                tx.set(lock, mapOf("campsiteId" to siteId, "reservationId" to id, "date" to date))
            }
            tx.update(ref, mapOf("status" to "confirmed", "updatedAt" to stamp()))
            tx.update(offerRef, mapOf("status" to "used", "updatedAt" to stamp()))
            tx.set(ref("notifications", "reservation_confirmed_$id"), notice(request, "confirmed"))
            true
        }.await().also { checkAccount(ownerId) }
    }

    /** Releases only matching canonical locks; an old rejection cannot reopen a newer acceptance. */
    internal suspend fun release(id: String, ownerId: String): Boolean {
        requireBookingId(id)
        return firestore.runTransaction { tx ->
            checkAccount(ownerId)
            val reference = ref("reservations", id)
            val request = tx.get(reference)
            if (!request.exists()) return@runTransaction false
            check(request.get("ownerId") == ownerId) { "Reservation belongs to another owner." }
            val rejected = request.get("status") == "rejected"
            val cancelling = request.get("status") == "confirmed" && request.get("cancellationRequested") == true
            if (!rejected && !cancelling) return@runTransaction false
            val marker = ref("reservationReleases", id)
            if (rejected && tx.get(marker).exists()) return@runTransaction false
            val siteId = request.idField("campsiteId")
            val offerRef = ref("offers", request.idField("offerId"))
            val offer = tx.get(offerRef)
            val locks = bookingNightKeys(request.time("startDate"), request.time("endDate"))
                .map { ref("bookedNights", "${siteId}_$it") }.map(tx::get)
            checkAccount(ownerId)
            locks.filter { it.exists() && it.get("reservationId") == id && it.get("campsiteId") == siteId }
                .forEach { tx.delete(it.reference) }
            if (rejected) {
                if (offer.get("status") == "pending" && offer.get("response") == "accept" && offer.get("reservationId") == id) {
                    check(offer.get("userId") == request.get("userId") && offer.get("lotteryId") == request.get("lotteryId"))
                    reopen(tx, offerRef)
                }
                if (request.get("cancellationRequested") == true) tx.update(reference,
                    mapOf("cancellationRequested" to false, "updatedAt" to stamp()))
                tx.set(marker, mapOf("reservationId" to id, "processedAt" to stamp()))
            } else {
                tx.update(reference, mapOf("status" to "cancelled", "cancellationRequested" to false, "updatedAt" to stamp()))
                tx.set(ref("notifications", "reservation_cancelled_$id"), notice(request, "cancelled"))
            }
            true
        }.await().also { checkAccount(ownerId) }
    }

    /** One immutable marker per decline, preserving all other winners and the original deadline. */
    internal suspend fun replace(id: String, ownerId: String): Boolean {
        requireBookingId(id)
        val initial = ref("offers", id).get(Source.SERVER).await()
        checkAccount(ownerId)
        if (!initial.exists() || initial.get("response") != "decline" || initial.get("status") != "pending") return false
        val lotteryId = initial.idField("lotteryId")
        val entries = firestore.collection("waitlist").whereEqualTo("lotteryId", lotteryId)
            .limit(101).get(Source.SERVER).await().documents
        val offers = firestore.collection("offers").whereEqualTo("lotteryId", lotteryId)
            .limit(101).get(Source.SERVER).await().documents
        checkAccount(ownerId)
        check(entries.size <= 100 && offers.size <= 100) { "Lottery exceeds the supported entrant limit." }
        val selected = offers.map { it.idField("userId") }.toSet()
        var candidate: DocumentSnapshot? = null
        for (entry in entries.filter { it.get("status") == "lost" && it.idField("userId") !in selected }.shuffled()) {
            if (entrantActive(lotteryId, entry.idField("userId"), ownerId)) { candidate = entry; break }
        }
        val chosenEntry = candidate
        return firestore.runTransaction { tx ->
            checkAccount(ownerId)
            val offer = tx.get(ref("offers", id))
            val lottery = tx.get(ref("lotteries", lotteryId))
            check(lottery.get("ownerId") == ownerId) { "Lottery belongs to another owner." }
            val marker = ref("offerReplacements", id)
            if (tx.get(marker).exists() || offer.get("status") != "pending" || offer.get("response") != "decline") {
                return@runTransaction false
            }
            check(id == "${lotteryId}_${offer.idField("userId")}" && offer.get("reservationId") == null)
            check(lottery.get("winnersDrawn") == true && offer.time("expiresAt") == lottery.time("bookingEndAt") &&
                offer.campsites() == lottery.campsites()) { "Decline disagrees with the original lottery." }
            val manifest = tx.get(ref("lotteryDraws", lotteryId))
            val entry = chosenEntry?.let { tx.get(it.reference) }
            val expired = now() >= offer.time("expiresAt")
            if (!expired && entry != null && (entry.get("status") != "lost" || entry.get("lotteryId") != lotteryId)) {
                // Another owner device selected this candidate. Re-query the pool on the next check.
                return@runTransaction false
            }
            val uid = if (expired) null else entry?.idField("userId")
            if (uid != null && manifest.exists()) {
                check(uid in (manifest.get("entrantIds") as? List<*>).orEmpty() &&
                    uid !in (manifest.get("winnerIds") as? List<*>).orEmpty()) { "Invalid replacement entrant." }
            }
            checkAccount(ownerId)
            if (uid != null) {
                val offerId = "${lotteryId}_$uid"
                tx.update(checkNotNull(entry).reference, mapOf("status" to "won", "updatedAt" to stamp()))
                tx.set(ref("offers", offerId), mapOf("lotteryId" to lotteryId, "userId" to uid,
                    "campsiteIds" to offer.campsites(), "status" to "pending", "response" to null,
                    "expiresAt" to offer.time("expiresAt"), "createdAt" to stamp(), "updatedAt" to stamp(),
                    "replacementFor" to id))
                val result = mapOf("type" to "lottery_won", "toUserId" to uid, "lotteryId" to lotteryId,
                    "offerId" to offerId, "createdAt" to stamp())
                tx.set(ref("notifications", "lottery_replacement_$id"), result)
                tx.set(ref("lotteryNotificationOutbox", "lottery_replacement_$id"),
                    result + mapOf("deliveryStatus" to "pending", "updatedAt" to stamp()))
            }
            tx.set(marker, mapOf("lotteryId" to lotteryId, "declinedOfferId" to id,
                "replacementUserId" to uid, "replacementOfferId" to uid?.let { "${lotteryId}_$it" }, "processedAt" to stamp()))
            true
        }.await().also { checkAccount(ownerId) }
    }

    private fun ref(collection: String, id: String) = firestore.collection(collection).document(id)
    /**
     * These paths intentionally contain no documents. A permitted missing read means eligible;
     * rules inspect only disabled state. Probe the owner first so missing deployment/auth permissions
     * cannot be mistaken for an ineligible entrant. Final writes recheck eligibility in rules.
     */
    private suspend fun entrantActive(lotteryId: String, uid: String, ownerId: String): Boolean {
        val eligibility = firestore.collection("lotteries").document(lotteryId).collection("bookingEligibility")
        eligibility.document(ownerId).get(Source.SERVER).await()
        checkAccount(ownerId)
        return try {
            eligibility.document(uid).get(Source.SERVER).await()
            checkAccount(ownerId)
            true
        } catch (error: FirebaseFirestoreException) {
            checkAccount(ownerId)
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw error
        }
    }
    private fun checkAccount(ownerId: String) {
        if (auth.currentUser?.uid != ownerId) throw SecurityException("The signed-in owner changed.")
    }
    private fun reopen(tx: Transaction, offer: DocumentReference) {
        tx.update(offer, mapOf("response" to null, "reservationId" to FieldValue.delete(),
            "respondedAt" to FieldValue.delete(), "updatedAt" to stamp()))
    }
    private fun notice(request: DocumentSnapshot, outcome: String): Map<String, Any?> = mapOf(
        "toUserId" to request.idField("userId"), "fromUserId" to null, "lotteryId" to request.idField("lotteryId"),
        "reservationId" to request.id, "type" to "reservation_$outcome",
        "title" to "Reservation $outcome", "body" to if (outcome == "confirmed")
            "Your campsite reservation is confirmed. View your booking details." else
            "Your reservation has been cancelled and its dates released.",
        "read" to false, "createdAt" to stamp(), "updatedAt" to stamp(),
    )
    private fun DocumentSnapshot.idField(field: String): String =
        checkNotNull(get(field) as? String) { "Missing $field." }.also(::requireBookingId)
    private fun DocumentSnapshot.time(field: String): Timestamp =
        checkNotNull(get(field) as? Timestamp) { "Invalid $field." }
    private fun DocumentSnapshot.campsites(): List<String> {
        val ids = checkNotNull(get("campsiteIds") as? List<*>) { "Missing campsite IDs." }
        check(ids.size in 1..8 && ids.distinct().size == ids.size)
        return ids.map { checkNotNull(it as? String).also(::requireBookingId) }
    }
    private fun stamp() = FieldValue.serverTimestamp()

    companion object {
        val instance by lazy { FirebaseLocalBookingRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
