/*
 * ReservationFormat.kt
 *
 * Purpose: Format reservation dates and status labels for display.
 * Role: View helpers in MVC; keeps reservation presentation consistent across screens.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import com.google.firebase.Timestamp
import com.example.legion_events.model.Reservation
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Formats a check-in or checkout date as its UTC calendar day. Stay dates are saved as UTC
 * midnights, matching owner-listed nights and S17, so the device's time zone must not shift them.
 */
internal fun reservationDateText(date: Timestamp): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(date.toDate())

/** Formats a real event time, such as a completed cancellation, in the device's local time zone. */
internal fun reservationEventDateText(date: Timestamp): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date.toDate())

/** Maps stored reservation statuses to readable labels, with a fallback for unknown values. */
internal fun reservationStatusText(status: String): String = when (status) {
    Reservation.STATUS_CONFIRMED -> "Confirmed"
    Reservation.STATUS_REQUESTED -> "Requested"
    "pending" -> "Pending"
    "completed" -> "Completed"
    "cancelled" -> "Cancelled"
    // Owner cancellations retain the existing rejected state; processing releases their nights.
    "rejected" -> "Cancelled"
    else -> "Unknown"
}
