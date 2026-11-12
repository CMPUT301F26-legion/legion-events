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

/** Formats an explicit check-in or checkout instant in the device's local time zone. */
internal fun reservationDateText(date: Timestamp): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date.toDate())

/** Maps stored reservation statuses to readable labels, with a fallback for unknown values. */
internal fun reservationStatusText(status: String): String = when (status) {
    Reservation.STATUS_CONFIRMED -> "Confirmed"
    "pending" -> "Pending"
    "completed" -> "Completed"
    "cancelled" -> "Cancelled"
    "rejected" -> "Rejected"
    else -> "Unknown"
}
