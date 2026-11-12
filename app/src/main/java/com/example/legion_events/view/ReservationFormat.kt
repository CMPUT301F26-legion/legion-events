package com.example.legion_events.view

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

/** Formats an explicit check-in or checkout instant in the device's local time zone. */
internal fun reservationDateText(date: Timestamp): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date.toDate())
