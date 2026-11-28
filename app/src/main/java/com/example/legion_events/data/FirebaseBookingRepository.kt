/*
 * FirebaseBookingRepository.kt
 * Purpose: Load winner choices and atomically record explicit entrant acceptance.
 * Role: Data layer in MVC; never confirms bookings or writes occupied nights.
 * Outstanding issues: The confirming worker and booking screens follow in PRs 2 and 3.
 */
package com.example.legion_events.data

import com.example.legion_events.model.BookingCampsite
import com.example.legion_events.model.BookingOffer
import com.example.legion_events.model.BookingOptions
import com.example.legion_events.model.BookingRequest
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.bookingNightKeys
import com.example.legion_events.model.isBookingNightKey
import com.example.legion_events.model.requireBookingId
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Uses server reads for recovery and choices. A stable request ID plus an offer claim makes
 * retries safe, including a successful commit whose response was lost. Transaction retries
 * re-read the offer and listing; an account change aborts before exposing another UID's data.
 */
class FirebaseBookingRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = { Timestamp.now() },
) : BookingRepository {
    override suspend fun getBookingOptions(offerId: String): BookingOptions {
        requireBookingId(offerId)
        val uid = currentUserId()
        val offerDocument = firestore.collection("offers").document(offerId).get(Source.SERVER).await()
        checkAccount(uid)
        val offer = offerDocument.toOwnedBookingOffer(uid)
        requireBookingId(offer.lotteryId)
        val lotteryDocument = firestore.collection("lotteries").document(offer.lotteryId).get(Source.SERVER).await()
        checkAccount(uid)
        val lottery = lotteryDocument.toLottery()
        validateOffer(offer, lottery, uid)
        val campsites = offer.campsiteIds.mapNotNull { id ->
            val document = try {
                firestore.collection("campsites").document(id).get(Source.SERVER).await()
            } catch (error: FirebaseFirestoreException) {
                checkAccount(uid)
                // Inactive listings are unreadable to entrants. Other readable offered sites
                // remain bookable; a network failure must still surface as an error.
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return@mapNotNull null
                throw error
            }
            checkAccount(uid)
            if (!document.exists() || document.get("active") != true || document.get("ownerId") != lottery.ownerId) {
                return@mapNotNull null
            }
            val campsite = document.toBookingCampsite(lottery.ownerId)
            val locks = firestore.collection("bookedNights").whereEqualTo("campsiteId", id).get(Source.SERVER).await()
            checkAccount(uid)
            val nights = locks.documents.map { lock ->
                val date = checkNotNull(lock.get("date") as? String) { "Occupied night has no date." }
                check(lock.get("campsiteId") == id && lock.id == "${id}_$date" &&
                    (lock.get("reservationId") as? String)?.isNotBlank() == true &&
                    isBookingNightKey(date)) { "Occupied night is invalid." }
                date
            }.toSet()
            BookingCampsite(campsite, nights)
        }
        checkAccount(uid)
        return BookingOptions(offer, lottery, campsites)
    }

    override suspend fun getRequestsForOffer(offerId: String): List<Reservation> {
        requireBookingId(offerId)
        val uid = currentUserId()
        return requestsForOffer(offerId, uid)
    }

    override suspend fun acceptOffer(request: BookingRequest): Reservation {
        request.validate()
        val uid = currentUserId()
        // Missing reservation reads are denied by the rules. A UID-scoped query safely recovers
        // a previous commit without widening document-read permissions for non-existent IDs.
        requestsForOffer(request.offerId, uid).firstOrNull { it.id == request.id }?.let {
            checkSameRequest(it, request)
            return it
        }
        val offerRef = firestore.collection("offers").document(request.offerId)
        val requestRef = firestore.collection("reservations").document(request.id)
        firestore.runTransaction<Unit> { transaction ->
            checkAccount(uid)
            val offer = transaction.get(offerRef).toOwnedBookingOffer(uid)
            requireBookingId(offer.lotteryId)
            // A concurrent retry may have committed between the query and this transaction.
            if (offer.reservationId == request.id &&
                offer.status in listOf(BookingOffer.STATUS_ACCEPTED, BookingOffer.STATUS_USED)) {
                val saved = transaction.get(requestRef).toOwnedRequest(uid)
                checkSameRequest(saved, request)
                return@runTransaction
            }
            val lottery = transaction.get(firestore.collection("lotteries").document(offer.lotteryId)).toLottery()
            validateOffer(offer, lottery, uid)
            check(BookingOptions(offer, lottery, emptyList()).canAccept(now())) { "This offer cannot currently be accepted." }
            check(request.campsiteId in offer.campsiteIds) { "This campsite is not included in your offer." }
            val campsite = transaction.get(firestore.collection("campsites").document(request.campsiteId))
                .toBookingCampsite(lottery.ownerId)
            request.validateFor(campsite)
            val nights = bookingNightKeys(request.startDate, request.endDate)
            check(nights.all { it in campsite.availableNights }) { "Choose consecutive owner-listed nights." }
            check(request.startDate.seconds >= now().seconds.floorDiv(86_400L) * 86_400L) { "Choose current or future camping dates." }
            checkAccount(uid)
            transaction.set(requestRef, mapOf(
                "lotteryId" to lottery.id, "offerId" to offer.id, "campsiteId" to campsite.id,
                "userId" to uid, "ownerId" to lottery.ownerId,
                "startDate" to request.startDate, "endDate" to request.endDate,
                "guestCount" to request.guestCount, "totalPrice" to request.totalPrice,
                "rulesAccepted" to true, "status" to Reservation.STATUS_REQUESTED,
                "cancellationRequested" to false,
                "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
            ))
            transaction.update(offerRef, mapOf(
                "response" to "accept", "reservationId" to request.id,
                "respondedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.await()
        checkAccount(uid)
        val saved = requestsForOffer(request.offerId, uid).firstOrNull { it.id == request.id }
        checkNotNull(saved) { "Reload your booking request to check its status." }
        checkSameRequest(saved, request)
        return saved
    }

    /** Queries only this account, checking ownership before parsing each returned document. */
    private suspend fun requestsForOffer(offerId: String, uid: String): List<Reservation> {
        val documents = firestore.collection("reservations").whereEqualTo("userId", uid)
            .whereEqualTo("offerId", offerId).get(Source.SERVER).await().documents
        checkAccount(uid)
        return documents.map {
            val request = it.toOwnedRequest(uid)
            check(request.offerId == offerId) { "Request belongs to another offer." }
            request
        }.sortedWith(compareByDescending<Reservation> { it.createdAt }.thenBy { it.id })
    }

    /** Resolves identity before any database access. */
    private fun currentUserId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /** Rejects results and writes after the authenticated account changes. */
    private fun checkAccount(uid: String) {
        if (auth.currentUser?.uid != uid) throw SecurityException("The signed-in account changed.")
    }

    /** Never interprets an invalid or legacy incomplete offer as permission to book. */
    private fun validateOffer(offer: BookingOffer, lottery: Lottery, uid: String) {
        checkAccount(uid)
        check(offer.id == "${lottery.id}_$uid" && offer.lotteryId == lottery.id &&
            lottery.ownerId.isNotBlank() && offer.campsiteIds.isNotEmpty() &&
            offer.campsiteIds.all { it in lottery.campsiteIds } &&
            offer.expiresAt == lottery.bookingEndAt && lottery.winnersDrawn) { "This offer has invalid booking details." }
    }

    /** Loads required booking fields strictly; display-only defaults must not authorize a request. */
    private fun DocumentSnapshot.toBookingCampsite(ownerId: String): Campsite {
        check(exists() && get("active") == true && get("ownerId") == ownerId) { "This campsite is unavailable." }
        check(get("maxGuests") is Long && getLong("maxGuests") in 1L..100L &&
            get("pricePerNight") is Long && getLong("pricePerNight") in 0L..10_000_000L &&
            get("cancellationPolicy") is String &&
            (get("rules") is String || (get("rules") is List<*> && (get("rules") as List<*>).all { it is String })) &&
            get("availableNights") is List<*> && (get("availableNights") as List<*>).let { nights ->
                nights.size <= 366 && nights.distinct().size == nights.size &&
                    nights.all { it is String && isBookingNightKey(it) }
            }) {
            "This campsite has invalid booking details."
        }
        return toOwnedCampsite()
    }

    /** Checks ownership before parsing reservation details. */
    private fun DocumentSnapshot.toOwnedRequest(uid: String): Reservation {
        if (requiredReservationString("userId") != uid) throw SecurityException("This request is not yours.")
        return toReservation()
    }

    /** An existing ID can never be reused to silently change the entrant's accepted selection. */
    private fun checkSameRequest(saved: Reservation, request: BookingRequest) {
        check(saved.offerId == request.offerId && saved.campsiteId == request.campsiteId &&
            saved.startDate == request.startDate && saved.endDate == request.endDate &&
            saved.guestCount == request.guestCount && saved.totalPrice == request.totalPrice && saved.rulesAccepted) {
            "This request ID already belongs to a different selection. Review a new request."
        }
    }

    companion object {
        /** Booking repository for the app's default Firebase project. */
        val instance by lazy { FirebaseBookingRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}
