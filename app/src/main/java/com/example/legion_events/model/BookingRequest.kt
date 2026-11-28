/*
 * BookingRequest.kt
 * Purpose: Shared selection, policy acceptance, and occupied-night contracts for booking.
 * Role: Model in MVC; selection never holds nights or confirms a reservation.
 * Outstanding issues: Confirmation and conflict recovery are implemented by the worker in PR 2.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import java.text.ParseException

/** Rules and cancellation policy the entrant reviewed before explicitly accepting an offer. */
data class BookingTerms(val rules: List<String>, val cancellationPolicy: String) {
    companion object {
        /** Captures the current terms; callers must require fresh acceptance if these change. */
        fun from(campsite: Campsite) = BookingTerms(campsite.rules.toList(), campsite.cancellationPolicy)
    }
}

/** An offered campsite and provisional occupied nights; the worker rechecks these at confirmation. */
data class BookingCampsite(val campsite: Campsite, val bookedNightKeys: Set<String>) {
    /** Owner-listed nights minus confirmed locks. Gaps cannot be bridged by a selected stay. */
    val availableNightKeys: Set<String> get() = campsite.availableNights.toSet() - bookedNightKeys
}

/** Server-read choices for a winner. The lottery dates are the submission window, not stay dates. */
data class BookingOptions(val offer: BookingOffer, val lottery: Lottery, val campsites: List<BookingCampsite>) {
    /** Only an open, unexpired offer inside its original booking window may be accepted. */
    fun canAccept(at: Timestamp): Boolean = offer.status == BookingOffer.STATUS_OPEN &&
        offer.expiresAt?.let { at < it } == true &&
        at >= lottery.bookingStartAt && at < lottery.bookingEndAt
}

/**
 * Immutable details explicitly accepted at review. Reuse [id] for retries of this attempt;
 * use a new ID after a rejected request and a fresh review. No identity comes from the caller.
 * Prices use integer CAD cents. Dates are UTC midnight night boundaries, checkout excluded.
 * [terms] must still match the listing when the request is submitted; [rulesAccepted] covers
 * both the rules and cancellation policy. A pending request is not a confirmed reservation.
 */
data class BookingRequest(
    val id: String,
    val offerId: String,
    val campsiteId: String,
    val startDate: Timestamp,
    val endDate: Timestamp,
    val guestCount: Int,
    val totalPrice: Long,
    val terms: BookingTerms,
    val rulesAccepted: Boolean,
) {
    /** Validates the reviewed request without changing any of its values. */
    fun validate() {
        listOf(id, offerId, campsiteId).forEach { requireBookingId(it) }
        bookingNightKeys(startDate, endDate)
        require(guestCount > 0) { "Choose at least one guest." }
        require(totalPrice >= 0) { "The booking total is invalid." }
        require(rulesAccepted) { "Accept the campsite rules and cancellation policy before reserving." }
    }

    /** Rejects changed terms or prices so a later screen can request a fresh review. */
    fun validateFor(campsite: Campsite) {
        validate()
        require(campsite.id == campsiteId && guestCount <= campsite.maxGuests) { "Choose a valid campsite and guest count." }
        require(terms == BookingTerms.from(campsite)) { "The campsite policies changed. Review and accept them again." }
        require(campsite.pricePerNight in 0..10_000_000L &&
            totalPrice == campsite.pricePerNight * bookingNightKeys(startDate, endDate).size) {
            "The booking price changed. Review the total again."
        }
    }
}

/** A canonical worker lock at bookedNights/{campsiteId}_{date}. Only the worker may write it. */
data class BookedNight(val campsiteId: String, val reservationId: String, val date: String) {
    init {
        requireBookingId(campsiteId)
        requireBookingId(reservationId)
        require(isBookingNightKey(date)) { "An occupied night must use a valid YYYY-MM-DD date." }
    }
    /** Stable lock key shared by competing bookings; never key a lock by reservation ID. */
    val id: String get() = "${campsiteId}_$date"
}

/** Rejects IDs that could address another document path. */
internal fun requireBookingId(id: String) = require(id.isNotBlank() && '/' !in id) { "A single document ID is required." }

/** Validates a real UTC calendar date instead of accepting an impossible display key. */
internal fun isBookingNightKey(key: String): Boolean {
    if (!key.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
    val format = nightFormat().apply { isLenient = false }
    return try {
        format.parse(key)?.let { format.format(it) == key } == true
    } catch (_: ParseException) { false }
}

/**
 * Enumerates a contiguous stay, capped at the listing's 366-night limit. Exact UTC boundaries
 * avoid device time zones, daylight-saving changes, and partial checkout nights.
 */
fun bookingNightKeys(startDate: Timestamp, endDate: Timestamp): List<String> {
    val day = 86_400L
    require(startDate.nanoseconds == 0 && endDate.nanoseconds == 0 &&
        startDate.seconds % day == 0L && endDate.seconds % day == 0L) { "Choose whole camping dates in UTC." }
    val count = (endDate.seconds - startDate.seconds) / day
    require(count in 1..366) { "Choose between 1 and 366 consecutive nights." }
    val format = nightFormat()
    return (0 until count.toInt()).map { format.format(Timestamp(startDate.seconds + it * day, 0).toDate()) }
}
