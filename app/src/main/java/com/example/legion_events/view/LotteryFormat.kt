package com.example.legion_events.view

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Formats a period stored the S15 way (closing at the start of the day after its last day)
 * as the days the owner picked, such as `Dec 1 – Dec 10, 2026`.
 * @param start When the period opens.
 * @param end When the period closes.
 * @return The first and last day, inclusive.
 */
internal fun periodText(start: Timestamp, end: Timestamp): String {
    val lastDay = Calendar.getInstance().apply { time = end.toDate(); add(Calendar.DAY_OF_MONTH, -1) }.time
    val short = SimpleDateFormat("MMM d", Locale.getDefault())
    val long = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return "${short.format(start.toDate())} – ${long.format(lastDay)}"
}

/**
 * Formats a join time such as `Joined Feb 2`.
 * @param joinedAt When the entrant joined, or null.
 * @return The caption, or `Joined` when the time is unknown.
 */
internal fun joinedText(joinedAt: Timestamp?): String =
    joinedAt?.let { "Joined " + SimpleDateFormat("MMM d", Locale.getDefault()).format(it.toDate()) } ?: "Joined"
