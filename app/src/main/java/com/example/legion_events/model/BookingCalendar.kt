/*
 * BookingCalendar.kt
 *
 * Purpose: Lay out one month of night keys for S17's availability calendar (US 02.06.01).
 * Role: Model helpers in MVC; pure date arithmetic in UTC, matching night keys elsewhere.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Returns the `YYYY-MM` month that contains an instant, in UTC.
 * @param at The instant.
 * @return The month key.
 */
fun monthOf(at: Timestamp): String = nightFormat().format(at.toDate()).take(7)

/**
 * Moves a month key forward or back.
 * @param month A `YYYY-MM` month key.
 * @param by Months to move; negative moves back.
 * @return The shifted `YYYY-MM` month key.
 */
fun shiftMonth(month: String, by: Int): String = monthCalendar(month).apply { add(Calendar.MONTH, by) }.let {
    String.format(Locale.US, "%04d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1)
}

/**
 * Lays out a month as Sunday-first calendar cells.
 * @param month A `YYYY-MM` month key.
 * @return Null for each blank cell before the 1st, then each day's `YYYY-MM-DD` night key.
 */
fun calendarCells(month: String): List<String?> {
    val calendar = monthCalendar(month)
    val blanks = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val days = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    return List<String?>(blanks) { null } + (1..days).map { day -> "$month-${day.toString().padStart(2, '0')}" }
}

/**
 * Names a month for display, such as "July 2027".
 * @param month A `YYYY-MM` month key.
 * @return The month name and year.
 */
fun monthTitle(month: String): String =
    SimpleDateFormat("MMMM yyyy", Locale.CANADA).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(monthCalendar(month).time)

/**
 * Parses a month key into a UTC calendar set to the 1st of that month.
 * @param month A `YYYY-MM` month key.
 * @return The calendar.
 * @throws IllegalArgumentException If the key is not a valid month.
 */
private fun monthCalendar(month: String): Calendar {
    val parts = month.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull()
    val number = parts.getOrNull(1)?.toIntOrNull()
    require(parts.size == 2 && year != null && number != null && number in 1..12) { "Invalid month $month." }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        clear()
        set(year, number - 1, 1)
    }
}
