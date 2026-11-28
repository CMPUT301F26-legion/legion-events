/*
 * BookingSelection.kt
 * Purpose: Validate the entrant's campsite/date/guest draft for US 01.04.01/02.
 * Role: Model in MVC; pure UTC date choices and exact price calculation.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Local draft only. Check-in and checkout are whole UTC calendar days, never device-local instants. */
data class BookingSelection(
    val campsiteId: String? = null,
    val checkInMillis: Long? = null,
    val checkOutMillis: Long? = null,
    val guests: Int = 1,
) {
    /** Returns a helpful first validation problem, or null for a complete available stay. */
    fun problem(choice: BookingCampsite?, at: Timestamp): String? {
        if (choice == null || choice.campsite.id != campsiteId) return "Choose an offered campsite."
        if (guests !in 1..choice.campsite.maxGuests) return "Choose a guest count within this campsite's capacity."
        val start = checkInMillis ?: return "Choose a check-in date."
        val end = checkOutMillis ?: return "Choose a checkout date."
        if (start !in bookingCheckIns(choice, at) || end !in bookingCheckouts(choice, start)) {
            return "These dates are unavailable. Choose consecutive available nights."
        }
        return null
    }

    /** Exact total for valid whole dates; availability and capacity are checked separately by [problem]. */
    fun total(campsite: Campsite): Long? {
        val start = checkInMillis ?: return null
        val end = checkOutMillis ?: return null
        if (start % BOOKING_DAY_MILLIS != 0L || end % BOOKING_DAY_MILLIS != 0L) return null
        val nights = (end - start) / BOOKING_DAY_MILLIS
        if (nights !in 1..366 || campsite.pricePerNight !in 0..10_000_000L) return null
        return nights * campsite.pricePerNight
    }
}

/** Milliseconds in one UTC calendar day; no daylight-saving conversions are applied. */
internal const val BOOKING_DAY_MILLIS = 86_400_000L

/** Available check-in days, excluding all dates before the current UTC day. */
fun bookingCheckIns(choice: BookingCampsite, at: Timestamp): Set<Long> {
    val today = at.seconds.floorDiv(86_400L) * BOOKING_DAY_MILLIS
    return choice.availableNightKeys.mapNotNull { key ->
        if (isBookingNightKey(key)) nightFormat().parse(key)?.time else null
    }.filter { it >= today }.toSet()
}

/** Possible checkout days after this check-in, stopping at the first missing or occupied night. */
fun bookingCheckouts(choice: BookingCampsite, checkInMillis: Long): Set<Long> {
    if (checkInMillis % BOOKING_DAY_MILLIS != 0L) return emptySet()
    val available = choice.availableNightKeys
    val format = nightFormat()
    return buildSet {
        for (offset in 0 until 366) {
            val night = checkInMillis + offset * BOOKING_DAY_MILLIS
            if (format.format(java.util.Date(night)) !in available) break
            add(night + BOOKING_DAY_MILLIS)
        }
    }
}
